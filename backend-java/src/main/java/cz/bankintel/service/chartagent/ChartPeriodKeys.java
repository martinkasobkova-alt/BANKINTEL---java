package cz.bankintel.service.chartagent;

import java.util.ArrayList;
import java.util.List;

public final class ChartPeriodKeys {

    private ChartPeriodKeys() {}

    public static int compare(String a, String b) {
        List<Integer> ka = digits(a);
        List<Integer> kb = digits(b);
        int len = Math.max(ka.size(), kb.size());
        for (int i = 0; i < len; i++) {
            int va = i < ka.size() ? ka.get(i) : 0;
            int vb = i < kb.size() ? kb.get(i) : 0;
            if (va != vb) {
                return Integer.compare(va, vb);
            }
        }
        return a.compareToIgnoreCase(b);
    }

    private static List<Integer> digits(String period) {
        List<Integer> nums = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (char ch : String.valueOf(period == null ? "" : period).toCharArray()) {
            if (Character.isDigit(ch)) {
                cur.append(ch);
            } else if (!cur.isEmpty()) {
                nums.add(Integer.parseInt(cur.toString()));
                cur.setLength(0);
            }
        }
        if (!cur.isEmpty()) {
            nums.add(Integer.parseInt(cur.toString()));
        }
        return nums;
    }
}
