"""Prints a compact summary of the active models' metadata (for logs and model cards).

    python -m app.training.summary
"""

from __future__ import annotations

from app.models import registry


def _fmt(value: float | None, digits: int = 3) -> str:
    return "-" if value is None else f"{value:.{digits}f}"


def main() -> None:
    active = registry.active_versions()
    print("active:", active)
    for name in ("pd", "churn"):
        if name not in active:
            continue
        _, m = registry.load(name)
        print(f"\n== {name} {m['version']} (best iteration {m['best_iteration']})")
        for period, info in m["data"]["periods"].items():
            print(f"  {period:10s} {info['from']}..{info['to']} rows={info['rows']} events={info['events']}")
        for model in ("logistic", "lightgbm_raw", "lightgbm"):
            for period in ("train", "validation", "test"):
                t = m["metrics"][model][period]
                print(f"  {model:13s} {period:10s} AUC={_fmt(t['auc'])} Gini={_fmt(t['gini'])} KS={_fmt(t['ks'])} "
                      f"Brier={_fmt(t['brier'], 5)} meanPD={_fmt(t['mean_prediction'], 4)} "
                      f"rate={_fmt(t['event_rate'], 4)}")
        p = m["performing_test"]
        print(f"  performing   AUC lightgbm={_fmt(p['lightgbm']['auc'])} logistic={_fmt(p['logistic']['auc'])} "
              f"rows={p['lightgbm']['rows']} events={p['lightgbm']['events']}")
        print(f"  calibration  mean predicted {m['calibration_test']['mean_predicted']:.4f} vs observed "
              f"{m['calibration_test']['observed_rate']:.4f}")
        print("  segments    ", {s: (_fmt(v['lightgbm']['auc']), v['lightgbm']['events'])
                                 for s, v in m["segments_test"].items()})
        print("  psi score   ", {k: round(v, 4) for k, v in m["psi"]["score"].items()})
        worst = sorted(m["psi"]["features"].items(), key=lambda kv: -kv[1]["test"])[:4]
        print("  psi worst   ", [(k, round(v["test"], 3)) for k, v in worst])
        print("  shap top    ", [(k, round(v, 3)) for k, v in list(m["shap_importance_test"].items())[:8]])
        print("  grades      ", [(g["grade"], g["rows"], g["events"]) for g in m["grades_test"]])
    if "nlp" in active:
        _, m = registry.load("nlp")
        print(f"\n== nlp {m['version']} variant {m['selected_variant']} peak rss {m['peak_rss_mb']} MB")
        for name, v in {**m["baselines"], **m["variants"]}.items():
            extra = ""
            if "held_out_merchants" in v:
                extra = (f" held-out merchants F1={v['held_out_merchants']['macro_f1']:.4f}"
                         f" unseen texts acc={_fmt(v['unseen_descriptions']['accuracy'], 4)}")
            print(f"  {name:16s} macro F1={v['macro_f1']:.4f} accuracy={v['accuracy']:.4f}{extra}")


if __name__ == "__main__":
    main()
