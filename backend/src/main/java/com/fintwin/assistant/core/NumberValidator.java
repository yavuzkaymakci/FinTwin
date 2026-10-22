package com.fintwin.assistant.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks that every percentage and money amount in an answer has a counterpart in the tool results (or in the
 * user's own question). Turkish number formats are understood ("%3,23", "8.010.634 TL", "5,7 milyon TL", "0,98
 * puan"); a value matches a source number directly or as a fraction x100, within the rounding the answer shows.
 * Unmatched numbers are reported, logged and stored in the audit log; the answer itself is not changed.
 */
public final class NumberValidator {

    /** @param unverified the numbers (as written) without a counterpart in the sources */
    public record Result(boolean passed, int checked, List<String> unverified) {
    }

    private static final int FLAGS = Pattern.UNICODE_CHARACTER_CLASS;
    private static final String NUM = "(\\d{1,3}(?:\\.\\d{3})+(?:,\\d+)?|\\d+(?:[.,]\\d+)?)";
    private static final Pattern PERCENT = Pattern.compile(
            "%\\s?" + NUM + "|" + NUM + "\\s?%|(?i:yüzde)\\s" + NUM + "|" + NUM + "\\s?(?i:puan|pp)\\b", FLAGS);
    private static final Pattern AMOUNT = Pattern.compile(
            NUM + "\\s?(?:(?i:(milyar|milyon|bin|mn|m|b|k))\\s?)?(?i:TL|₺|TRY|lira)\\b", FLAGS);
    private static final Pattern SOURCE_JSON = Pattern.compile("-?\\d+(?:\\.\\d+)?(?:[eE][-+]?\\d+)?");
    private static final Pattern SOURCE_TEXT = Pattern.compile(NUM);

    record Found(String text, double value, double tolerance, boolean percent) {
    }

    public Result validate(String answer, List<String> toolResults, String question) {
        List<Double> sources = new ArrayList<>();
        for (String result : toolResults) {
            if (result == null) {
                continue;
            }
            Matcher m = SOURCE_JSON.matcher(result);
            while (m.find()) {
                try {
                    sources.add(Math.abs(Double.parseDouble(m.group())));
                } catch (NumberFormatException ignored) {
                    // not a number after all
                }
            }
        }
        if (question != null) {
            Matcher m = SOURCE_TEXT.matcher(question);
            while (m.find()) {
                sources.add(Math.abs(parse(m.group())[0]));
            }
        }
        List<Found> found = extract(answer == null ? "" : answer);
        List<String> unverified = new ArrayList<>();
        for (Found f : found) {
            if (!matches(f, sources)) {
                unverified.add(f.text());
            }
        }
        return new Result(unverified.isEmpty(), found.size(), unverified);
    }

    private static boolean matches(Found f, List<Double> sources) {
        for (double s : sources) {
            if (close(f.value(), s, f.tolerance()) || (f.percent() && close(f.value(), s * 100, f.tolerance()))) {
                return true;
            }
        }
        return false;
    }

    private static boolean close(double value, double source, double tolerance) {
        return Math.abs(value - source) <= Math.max(tolerance, Math.abs(source) * 0.005) + 1e-9;
    }

    static List<Found> extract(String text) {
        List<Found> out = new ArrayList<>();
        Matcher p = PERCENT.matcher(text);
        while (p.find()) {
            double[] parsed = parse(first(p));
            out.add(new Found(p.group().trim(), parsed[0], 0.5 * Math.pow(10, -parsed[1]), true));
        }
        Matcher a = AMOUNT.matcher(text);
        while (a.find()) {
            double[] parsed = parse(a.group(1));
            double scale = scale(a.group(2));
            out.add(new Found(a.group().trim(), parsed[0] * scale, 0.5 * Math.pow(10, -parsed[1]) * scale, false));
        }
        return out;
    }

    private static String first(Matcher m) {
        for (int i = 1; i <= m.groupCount(); i++) {
            if (m.group(i) != null) {
                return m.group(i);
            }
        }
        return m.group();
    }

    private static double scale(String unit) {
        if (unit == null) {
            return 1;
        }
        return switch (unit.toLowerCase(Locale.ROOT)) {
            case "milyar", "b" -> 1e9;
            case "milyon", "mn", "m" -> 1e6;
            case "bin", "k" -> 1e3;
            default -> 1;
        };
    }

    /** [value, decimals] of a Turkish (or plain) formatted number. */
    static double[] parse(String token) {
        String t = token;
        if (t.contains(",") && t.contains(".")) {
            t = t.replace(".", "").replace(',', '.');
        } else if (t.contains(",")) {
            t = t.replace(',', '.');
        } else if (t.matches("\\d{1,3}(\\.\\d{3})+")) {
            t = t.replace(".", "");
        }
        int dot = t.indexOf('.');
        int decimals = dot < 0 ? 0 : t.length() - dot - 1;
        return new double[]{Double.parseDouble(t), decimals};
    }
}
