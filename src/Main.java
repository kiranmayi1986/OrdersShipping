
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class Main {
    private static final double DEFAULT_LAMBDA = 2.0;          // $ per order-day of delivery time
    private static final long DEFAULT_NODE_BUDGET = 20_000_000L;

    public static void main(String[] args) throws Exception {
        Path dir = Paths.get("data");
        double lambda = DEFAULT_LAMBDA;
        long budget = DEFAULT_NODE_BUDGET;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--lambda": lambda = Double.parseDouble(args[++i]); break;
                case "--node-budget": budget = Long.parseLong(args[++i]); break;
                case "-h": case "--help": usage(); return;
                default: dir = Paths.get(args[i]);
            }
        }
        if (lambda < 0) throw new IllegalArgumentException("--lambda must be >= 0");

        Model.Data d = Model.load(dir);
        Planner planner = new Planner(d, lambda);
        Planner.Result res = planner.solve(budget);
        Planner.Plan plan = res.plan();
        planner.verify(plan);
        Planner.Plan baseline = planner.evaluate(planner.greedy());
        planner.verify(baseline);

        int nFc = d.fcs.size();
        out("FULFILLMENT PLAN  (data: %s)%n", dir);
        out("Objective : total cost ($ shipping + $ handling per package) + $%.2f per order-day of delivery time%n", lambda);
        out("Search    : %s, %,d nodes%n%n", res.optimal()
                ? "exhaustive branch-and-bound, proven optimal"
                : "node budget exhausted, best plan found (not proven optimal)", res.nodes());

        // ---- shipments ----
        out("SHIPMENTS%n");
        out("%-3s %-11s %-3s %-8s %-8s %6s %5s %6s %4s  %s%n", "#", "From", "To", "Customer", "Orders", "Ship", "Hand", "Total", "Days", "Items");
        int n = 1;
        for (Planner.Shipment s : plan.shipments) {
            out("%-3d %-11s %-3s %-8s %-8s %6.2f %5.2f %6.2f %4d  %s%n", n++, s.fc(), s.shipTo(), s.customer(),
                    String.join("+", s.orders()), s.shippingCost(), s.handlingCost(), s.total(), s.days(),
                    String.join(", ", s.items()));
        }

        // ---- orders ----
        Map<String, Set<String>> orderFcs = new LinkedHashMap<>();
        for (String o : d.orders) orderFcs.put(o, new LinkedHashSet<>());
        for (int li = 0; li < d.lines.size(); li++) {
            for (int f = 0; f < nFc; f++) {
                if (plan.alloc[li][f] > 0) orderFcs.get(d.lines.get(li).order()).add(d.fcs.get(f));
            }
        }
        out("%nORDERS%n");
        out("%-6s %-8s %-4s %-26s %s%n", "Order", "Customer", "To", "Shipped from", "Delivery days");
        int splitOrders = 0, completeOrders = 0;
        for (String o : d.orders) {
            Set<String> fcs = orderFcs.get(o);
            if (fcs.size() > 1) splitOrders++;
            int missing = 0;
            for (int li = 0; li < d.lines.size(); li++) {
                if (d.lines.get(li).order().equals(o)) missing += plan.alloc[li][nFc];
            }
            if (missing == 0) completeOrders++;
            String from = String.join(", ", fcs) + (missing > 0 ? " (UNFULFILLED x" + missing + ")" : "");
            out("%-6s %-8s %-4s %-26s %d%n", o, d.customerOf(o), d.destOf(o), from, plan.orderDays.get(o));
        }

        // ---- metrics ----
        int totalUnits = 0;
        for (Model.Line l : d.lines) totalUnits += l.qty();
        int shippedUnits = totalUnits - plan.unfulfilledUnits;
        double sumDays = 0;
        int maxDays = 0;
        for (int v : plan.orderDays.values()) { sumDays += v; maxDays = Math.max(maxDays, v); }
        double avgDays = sumDays / d.orders.size();
        double consolidationSaving = 0;
        int packagesWithoutConsolidation = 0;
        for (Planner.Shipment s : plan.shipments) {
            consolidationSaving += (s.orders().size() - 1) * s.total();
            packagesWithoutConsolidation += s.orders().size();
        }

        out("%nMETRICS%n");
        out("  Shipments (packages)        : %d  (%d without consolidation)%n", plan.shipments.size(), packagesWithoutConsolidation);
        out("  Shipping cost               : $%.2f%n", plan.shippingCost);
        out("  Handling cost               : $%.2f%n", plan.handlingCost);
        out("  TOTAL COST                  : $%.2f%n", plan.totalCost());
        out("  Saved by consolidation      : $%.2f%n", consolidationSaving);
        out("  Cost per order / per unit   : $%.2f / $%.2f%n", plan.totalCost() / d.orders.size(), shippedUnits == 0 ? 0 : plan.totalCost() / shippedUnits);
        out("  Delivery days (order)       : avg %.2f, max %d%n", avgDays, maxDays);
        out("  Delay penalty / objective   : $%.2f / %.2f%n", plan.delayPenalty, plan.objective);
        out("  Orders complete / split     : %d of %d / %d%n", completeOrders, d.orders.size(), splitOrders);
        out("  Units fulfilled             : %d of %d%n", shippedUnits, totalUnits);

        // ---- inventory ----
        int[][] used = new int[nFc][d.skus.size()];
        for (int li = 0; li < d.lines.size(); li++) {
            int s = d.skus.indexOf(d.lines.get(li).sku());
            for (int f = 0; f < nFc; f++) used[f][s] += plan.alloc[li][f];
        }
        out("%nINVENTORY  (units used / units on hand)%n");
        StringBuilder head = new StringBuilder(String.format(Locale.ROOT, "%-11s", "FC"));
        for (String s : d.skus) head.append(String.format(Locale.ROOT, " %6s", s));
        head.append(String.format(Locale.ROOT, " %9s", "total"));
        out("%s%n", head);
        for (int f = 0; f < nFc; f++) {
            StringBuilder row = new StringBuilder(String.format(Locale.ROOT, "%-11s", d.fcs.get(f)));
            int u = 0, h = 0;
            for (int s = 0; s < d.skus.size(); s++) {
                row.append(String.format(Locale.ROOT, " %6s", used[f][s] + "/" + d.inventory[f][s]));
                u += used[f][s];
                h += d.inventory[f][s];
            }
            row.append(String.format(Locale.ROOT, " %9s", u + "/" + h));
            out("%s%n", row);
        }

        // ---- baseline comparison ----
        double bAvg = 0;
        for (int v : baseline.orderDays.values()) bAvg += v;
        bAvg /= d.orders.size();
        out("%nBASELINE vs PLAN%n");
        out("  Baseline = per-unit greedy (cheapest/fastest in-stock FC for each unit, no look-ahead; packages still merge if they happen to share an FC)%n");
        out("%-22s %12s %12s%n", "", "Baseline", "Plan");
        out("%-22s %12d %12d%n", "Shipments", baseline.shipments.size(), plan.shipments.size());
        out("%-22s %12s %12s%n", "Total cost", money(baseline.totalCost()), money(plan.totalCost()));
        out("%-22s %12.2f %12.2f%n", "Avg delivery days", bAvg, avgDays);
        out("%-22s %12.2f %12.2f%n", "Objective", baseline.objective, plan.objective);
    }

    private static String money(double v) {
        return String.format(Locale.ROOT, "$%.2f", v);
    }

    private static void out(String fmt, Object... a) {
        System.out.print(String.format(Locale.ROOT, fmt, a));
    }

    private static void usage() {
        System.out.println("Usage: java -cp out Main [dataDir] [--lambda <$ per order-day>] [--node-budget <n>]");
        System.out.println("  dataDir defaults to ./data and must contain inventory.json, shipping.json, orders.json");
    }
}
