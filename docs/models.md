# Model cards — FinTwin ML service

Three models are served by `ml-service`. Each one is trained offline by a CLI, versioned in
`ml-service/artifacts/models/<name>/<version>/` (`model.joblib` + `metadata.json`), and activated through
`artifacts/models/active.json`. Artifacts are not committed. To re-create them, run `make ml-export-labels ml-train ml-train-nlp ml-evaluate`. The figures
below come from the active versions: PD `20261022-065226`, churn `20261022-065240`, NLP `20261022-065112`.
All data is synthetic (see [data-generation.md](data-generation.md)), so absolute performance is higher than it
would be on a real portfolio.

## Common design (PD and churn)

| Aspect | Choice |
|--------|--------|
| Unit | customer × observation month (`as_of` = month start) |
| Features | `twin.customer_twin_snapshot.features` at `as_of`, which is point-in-time because a snapshot only contains events before `as_of`. The twin computes them from Kafka events only; `app/features.py` derives ratios and shares. |
| Label | `DEFAULT` / `CHURN` in `public.customer_event` with `as_of ≤ event_date < as_of + 6 months` |
| Incomplete windows | excluded: `as_of + 6 months > data end (2026-10-01)`, so the last observation month is 2026-04 |
| Split (out-of-time) | train 2024-11 … 2025-06, validation 2025-07 … 2025-10, test 2025-11 … 2026-04 |
| Champion | LightGBM (depth 5, 15 leaves, min 100 rows per leaf, early stopping on validation, no class weights) + isotonic calibration on validation |
| Challenger | standardised logistic regression (C = 0.3, balanced) + Platt scaling on validation |
| Master scale | 10 grades on fixed PD bounds 0.1 / 0.25 / 0.5 / 1 / 2 / 4 / 8 / 15 / 30 % (`app/grades.py`) |
| Explanations | SHAP TreeExplainer on the raw margin, summed per feature (segment one-hot → one value). The top 5 risk-increasing reasons are rendered as EN/TR text, compared with the training-portfolio mean. |
| Data access | role `fintwin_ml`, read-only, **no privileges on `ground_truth`** (Flyway V8) |

**Leakage guards** (pytest):
- `test_dataset.py`: changing data after `as_of` never changes the features; label-window boundaries are tested; incomplete windows are dropped.
- `test_point_in_time_db.py`: for every sampled training row, the last event folded into the snapshot occurred before `as_of`.
- `test_ground_truth_isolation.py`: no module outside `app/labeling` and `app/evaluation` mentions the hidden-variable schema (source scan), and `training`/`scoring`/`api` cannot import those two packages (AST check). It also checks that `fintwin_ml` gets a permission error on that schema.

**Feature set** (21 + segment):
- Credit: utilisation, debt service to income, card and total debt to income, liquidity buffer (months), deposit share.
- Income and spending: spending trend (30 vs 90 days), income CV, salary-day irregularity, no-income flag.
- Repayment history: late payments, days since the last late payment.
- Products: active loans, has card, USD holder.
- Spending mix: cash, rent, discretionary, e-commerce and essentials shares.

Monetary amounts enter only as ratios to income, so inflation (about 3 %/month in the macro path) does not shift
the feature distributions.

## PD — 6-month probability of default

Population: customers with credit exposure (card or loan).

Rows (events):
- train 26,241 (392)
- validation 12,908 (262)
- test 19,154 (344)

| Test period | AUC | Gini | KS | Brier | Mean PD | Observed |
|-------------|-----|------|----|-------|---------|----------|
| **LightGBM + isotonic** | **0.972** | **0.945** | **0.845** | **0.01275** | 1.90 % | 1.80 % |
| Logistic regression + Platt | 0.951 | 0.903 | 0.806 | 0.01468 | 2.37 % | 1.80 % |

- **Performing book** (no late payment in the 90 days before `as_of`; 17,210 rows, 67 events): LightGBM AUC 0.964, LR 0.932. Late payments are strong but not the only signal: the model also ranks customers who have not missed a payment yet.
- **Calibration:**
  - Mean PD 1.90 % vs 1.80 % observed. The top decile predicts 16.5 % and observes 16.6 %.
  - LR over-predicts the top decile (18.1 % vs 15.4 %) and the overall rate.
- **Grades** (test, observed rate): monotonic from grade 5 upwards.

  | Grade | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 | 10 |
  |---|---|---|---|---|---|---|---|---|---|---|
  | Observed rate | 0.01 % | 0 % | 0.29 % | 0.37 % | 0.88 % | 2.06 % | 4.16 % | 14.6 % | 29.7 % | 44.4 % |

  Grades 1–4 hold almost no events, so their rates rest on 0–2 defaults.
- **Segments** (test AUC): WHITE_COLLAR 0.993, RETIREE 0.992, MIN_WAGE 0.958, SELF_EMPLOYED 0.952, STUDENT 0.885. STUDENT is the weakest segment: thin income and credit history.
- **Top features** (mean |SHAP|, test): liquidity buffer 0.65, utilisation 0.41, debt service to income 0.27, days since last late payment 0.24, card debt to income 0.18, segment 0.15.
- **Stability:** score PSI train→validation 0.005, train→test 0.005 (stable).
- **Feature drift:** feature PSI > 0.25 for `salary_day_deviation` (0.88), `income_cv` (0.52), `expense_to_income` (0.41) and `spend_trend` (0.45). This is twin history warm-up: the first observation months have short income histories. It does not hurt the score PSI or test discrimination, but it would be monitored in production.

**Hidden-stress evaluation** (`app/evaluation/stress_relationship.py`, role `fintwin_ml_labels`; the only code
that reads the hidden stress variable). Test period, 19,154 rows:

- Spearman(PD, hidden `financial_stress`) = **0.632**. Pearson = 0.472.
- AUC of the model 0.972 vs AUC of the hidden stress itself 0.948. The model sees late payments, which the latent stress precedes but does not fully determine, so the model can rank defaults better than the latent variable alone.
- Mean PD rises monotonically across stress deciles:

  | Stress decile | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 | 10 |
  |---|---|---|---|---|---|---|---|---|---|---|
  | Mean PD | 0.02 % | 0.03 % | 0.08 % | 0.16 % | 0.35 % | 0.58 % | 1.1 % | 1.7 % | 3.5 % | 11.5 % |
  | Observed default rate | 0 % | 0 % | 0 % | 0 % | 0.05 % | 0.05 % | 0.21 % | 0.52 % | 2.8 % | 14.3 % |

  The model recovers the generator's causal driver from behaviour alone.

## Churn — 6-month probability of attrition

Population: all active customers.

Rows (events):
- train 34,253 (813)
- validation 17,364 (499)
- test 26,194 (756)

| Test period | AUC | Gini | KS | Brier | Mean | Observed |
|-------------|-----|------|----|-------|------|----------|
| **LightGBM + isotonic** | **0.761** | **0.522** | **0.364** | **0.02547** | 3.12 % | 2.89 % |
| Logistic regression + Platt | 0.748 | 0.495 | 0.371 | 0.02720 | 3.14 % | 2.89 % |

- Calibration by decile is close: top decile 11.1 % predicted vs 10.6 % observed.
- **Grades** are monotonic from grade 3 onwards. Grade 10 has a 67 % observed churn rate.
- **Segments** (test AUC): WHITE_COLLAR 0.752, MIN_WAGE 0.751, SELF_EMPLOYED 0.737, STUDENT 0.700, RETIREE 0.616 (few events).
- **Top features** (mean |SHAP|): active loans 0.69 (a customer with an open loan rarely leaves), utilisation 0.34, total debt to income 0.34, spending trend 0.19, expense to income 0.15.
- **Score PSI:** validation 0.011, test 0.030.
- **Limitations:**
  - Churn is driven by dissatisfaction and opportunity, which leave weaker traces in transactions. Discrimination is therefore moderate.
  - LightGBM fits the training period much better (AUC 0.913) than later periods. Early stopping keeps it ahead of LR on validation and test, but the gap is an overfitting signal, and LR is a credible fallback.

## Transaction categorisation (NLP)

| Aspect | Choice |
|--------|--------|
| Task | 21 categories from the raw statement text (+ channel, direction, account type); no MCC |
| Labels | the generator's true category, exported **once** by `app/labeling/export_transaction_labels.py` (role `fintwin_ml_labels`) to `artifacts/data/transaction_labels.parquet`. **It stands in for a hand-labelled training set** a bank would build; the training code reads only that file. |
| Sample | stratified, at most 12,000 rows per category (246,802 rows; FX has 6,802) |
| Split | by time (test 2026-04 … 2026-09), plus a **merchant hold-out**: merchants with `merchant_id % 5 == 0` are never trained on, so the test measures generalisation to unseen merchants rather than template memorisation |
| Baseline | the twin's rule classifier (`TransactionClassifier`, ported to `app/training/rules.py`) |
| Models | (a) char_wb 2–4-gram TF-IDF; (b) char 2–4 + word 1–2-gram TF-IDF; both + one-hot context → SGD (log loss). Selected by validation macro F1. |
| Resources | ~13 s fit per variant, peak RSS ~465 MB |

| Test (63,246 rows) | Macro F1 | Accuracy |
|--------------------|----------|----------|
| Rules, with MCC (current twin setup) | 0.831 | 0.880 |
| Rules, text only | 0.196 | 0.245 |
| Model, char n-grams | 0.951 | 0.949 |
| **Model, char + word n-grams (selected)** | **0.955** | **0.952** |
| … on held-out merchants only (8,679 rows) | 0.722 | 0.648 |
| … on descriptions never seen in training (23,487 rows) | — | 0.870 |

- **Where the rules fail** even with MCC: CARD_PAYMENT, LOAN and FX all land in TRANSFER (F1 0). The model separates them almost perfectly from text and direction.
- **Where the model is weaker:**
  - ECOMMERCE (F1 0.69), TRAVEL 0.77, TELECOM 0.86 and UTILITIES 0.89.
  - On unseen merchants, TELECOM and TRAVEL brand names carry no category cue. The model falls back to UTILITIES and ECOMMERCE, and recall drops (TRAVEL 0.14, TELECOM 0).
  - In production, merchant names would be enriched with an MCC lookup, and the rules would remain a fallback for known MCCs.
- Per-class precision, recall and confusion matrices for every variant and baseline are in the version's `metadata.json`.

## Serving and integration

- `GET /v1/models/active`: active versions with metadata summaries.
- `POST /v1/risk/score`, `POST /v1/risk/score/batch` (≤ 1000): PD, churn, risk grade and the top 5 reasons, with model versions. `pdApplicable=false` when there is no credit exposure.
- `POST /v1/risk/explain`: all SHAP contributions.
- `POST /v1/categorize`: category, probability and top 3 alternatives.
- If no active model exists, the service returns 503 `MODEL_UNAVAILABLE`.
- Backend (`com.fintwin.twin.risk`):
  - `MlServiceClient` uses RestClient (connect timeout 2 s, read timeout 30 s), Resilience4j retry (3 attempts, exponential backoff) and a circuit breaker (50 % of the last 10 calls). An outage returns 503 `ML_SERVICE_UNAVAILABLE` and never crashes the backend.
  - Portfolio scoring: `POST /api/admin/risk/score-portfolio` (background, batches of 500), `GET /api/admin/risk/status`.
  - Results go to `twin.customer_risk_score`, which keeps history as an audit trail with model versions and `features_as_of`.
  - `GET /api/twins/{id}/risk[?refresh=true]`.
