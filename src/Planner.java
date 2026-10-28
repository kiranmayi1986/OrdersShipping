
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Builds a fulfillment plan.
 *
 * Decision: for every (order, SKU) line, how many units come from each FC.
 * A "shipment" is one package = (customer, destination, FC): all units that one customer
 * receives at one destination from one FC travel together, so orders are consolidated
 * automatically whenever the same FC serves them.
 *
 * Objective (minimised):
 *   sum over shipments of (shipping + handling)
 *   + lambda * sum over orders of (order delivery days)      [order days = slowest shipment]
 *   + UNFULFILLED_PENALTY * unfulfilled units                 [only if stock is insufficient]
 *
 * Solved exactly with depth-first branch and bound (every term only grows as lines are
 * added, so the partial objective is a valid lower bound). A greedy plan seeds the incumbent
 * and doubles as the comparison baseline. If a node budget is hit, the best plan found so
 * far is returned and flagged as not proven optimal.
 */
final class Planner {
    static final double UNFULFILLED_PENALTY = 1000.0;
    private static final double EPS = 1e-9;

    record Shipment(String customer, String shipTo, String fc, int fcIdx, double shippingCost,
                    double handlingCost, int days, List<String> orders, List<String> items) {
        double total() { return shippingCost + handlingCost; }
    }

    static final class Plan {
        final int[][] alloc;                    // [line][fc] units; column nFc = unfulfilled
        final List<Shipment> shipments;
        final Map<String, Integer> orderDays;   // order -> delivery days (0 if nothing shipped)
        final double shippingCost, handlingCost, delayPenalty, objective;
        final int unfulfilledUnits;

        Plan(int[][] alloc, List<Shipment> shipments, Map<String, Integer> orderDays,
             double lambda, int unfulfilledUnits) {
            this.alloc = alloc;
            this.shipments = shipments;
            this.orderDays = orderDays;
            double ship = 0, hand = 0;
            for (Shipment s : shipments) { ship += s.shippingCost(); hand += s.handlingCost(); }
            this.shippingCost = ship;
            this.handlingCost = hand;
            int days = 0;
            for (int v : orderDays.values()) days += v;
            this.delayPenalty = lambda * days;
            this.unfulfilledUnits = unfulfilledUnits;
            this.objective = ship + hand + delayPenalty + UNFULFILLED_PENALTY * unfulfilledUnits;
        }

        double totalCost() { return shippingCost + handlingCost; }
    }

    record Result(Plan plan, boolean optimal, long nodes) {}

    private final Model.Data d;
    private final double lambda;
    private final int nL, nFc, nOrders, nKeys;
    private final int[] qty, skuIdx, orderIdx;
    private final int[][] keyId;               // [line][fc] -> shipment-key id
    private final double[] keyCost;            // shipping + handling for that key
    private final int[] keyDays;

    // branch-and-bound state
    private int[][] remaining;
    private int[] useCount, orderMax;
    private int[][] cur, bestAlloc;
    private double bestObj;
    private long nodes, budget;
    private boolean aborted;

    Planner(Model.Data d, double lambda) {
        this.d = d;
        this.lambda = lambda;
        this.nL = d.lines.size();
        this.nFc = d.fcs.size();
        this.nOrders = d.orders.size();
        qty = new int[nL];
        skuIdx = new int[nL];
        orderIdx = new int[nL];
        keyId = new int[nL][nFc];
        Map<String, Integer> ids = new HashMap<>();
        List<double[]> keyInfo = new ArrayList<>();
        for (int li = 0; li < nL; li++) {
            Model.Line ln = d.lines.get(li);
            qty[li] = ln.qty();
            skuIdx[li] = d.skus.indexOf(ln.sku());
            orderIdx[li] = d.orders.indexOf(ln.order());
            for (int f = 0; f < nFc; f++) {
                String key = ln.customer() + "|" + ln.shipTo() + "|" + f;
                Integer id = ids.get(key);
                if (id == null) {
                    id = ids.size();
                    ids.put(key, id);
                    Model.Route r = d.routes.get(ln.shipTo())[f];
                    keyInfo.add(new double[] { r.cost() + d.handling, r.days() });
                }
                keyId[li][f] = id;
            }
        }
        nKeys = ids.size();
        keyCost = new double[nKeys];
        keyDays = new int[nKeys];
        for (int k = 0; k < nKeys; k++) {
            keyCost[k] = keyInfo.get(k)[0];
            keyDays[k] = (int) keyInfo.get(k)[1];
        }
    }

    // ------------------------------------------------------------------ evaluation

    private static final class Builder {
        final String customer, shipTo;
        final int fcIdx, days;
        final double shipping;
        final LinkedHashSet<String> orders = new LinkedHashSet<>();
        final List<String> items = new ArrayList<>();

        Builder(String customer, String shipTo, int fcIdx, Model.Route r) {
            this.customer = customer;
            this.shipTo = shipTo;
            this.fcIdx = fcIdx;
            this.shipping = r.cost();
            this.days = r.days();
        }
    }

    Plan evaluate(int[][] alloc) {
        Map<String, Builder> builders = new LinkedHashMap<>();
        Map<String, Integer> orderDays = new LinkedHashMap<>();
        for (String o : d.orders) orderDays.put(o, 0);
        int unfulfilled = 0;
        for (int li = 0; li < nL; li++) {
            Model.Line ln = d.lines.get(li);
            unfulfilled += alloc[li][nFc];
            for (int f = 0; f < nFc; f++) {
                int u = alloc[li][f];
                if (u == 0) continue;
                String key = ln.customer() + "|" + ln.shipTo() + "|" + f;
                Builder b = builders.get(key);
                if (b == null) {
                    b = new Builder(ln.customer(), ln.shipTo(), f, d.routes.get(ln.shipTo())[f]);
                    builders.put(key, b);
                }
                b.orders.add(ln.order());
                b.items.add(ln.order() + ":" + ln.sku() + " x" + u);
                orderDays.merge(ln.order(), b.days, Math::max);
            }
        }
        List<Shipment> shipments = new ArrayList<>();
        for (Builder b : builders.values()) {
            shipments.add(new Shipment(b.customer, b.shipTo, d.fcs.get(b.fcIdx), b.fcIdx, b.shipping,
                    d.handling, b.days, new ArrayList<>(b.orders), b.items));
        }
        shipments.sort(Comparator.comparingInt(Shipment::fcIdx)
                .thenComparing(Shipment::customer).thenComparing(Shipment::shipTo));
        return new Plan(alloc, shipments, orderDays, lambda, unfulfilled);
    }

    /** Independent safety check: every line fully accounted for, no SKU over-allocated. */
    void verify(Plan p) {
        int[][] used = new int[nFc][d.skus.size()];
        for (int li = 0; li < nL; li++) {
            int sum = 0;
            for (int f = 0; f <= nFc; f++) {
                if (p.alloc[li][f] < 0) throw new IllegalStateException("negative allocation on line " + li);
                sum += p.alloc[li][f];
                if (f < nFc) used[f][skuIdx[li]] += p.alloc[li][f];
            }
            if (sum != qty[li]) throw new IllegalStateException("line " + li + " allocates " + sum + " of " + qty[li]);
        }
        for (int f = 0; f < nFc; f++) {
            for (int s = 0; s < d.skus.size(); s++) {
                if (used[f][s] > d.inventory[f][s]) {
                    throw new IllegalStateException("over-allocated " + d.skus.get(s) + " at " + d.fcs.get(f));
                }
            }
        }
    }

    // ------------------------------------------------------------------ greedy baseline

    /** Per unit: pick the in-stock FC with the lowest (shipping + lambda * days). No look-ahead. */
    int[][] greedy() {
        int[][] rem = new int[nFc][];
        for (int f = 0; f < nFc; f++) rem[f] = d.inventory[f].clone();
        int[][] a = new int[nL][nFc + 1];
        for (int li = 0; li < nL; li++) {
            Model.Route[] rs = d.routes.get(d.lines.get(li).shipTo());
            for (int unit = 0; unit < qty[li]; unit++) {
                int best = -1;
                double bestScore = 0;
                for (int f = 0; f < nFc; f++) {
                    if (rem[f][skuIdx[li]] <= 0) continue;
                    double score = rs[f].cost() + lambda * rs[f].days();
                    if (best < 0 || score < bestScore - EPS) { best = f; bestScore = score; }
                }
                if (best < 0) {
                    a[li][nFc]++;
                } else {
                    a[li][best]++;
                    rem[best][skuIdx[li]]--;
                }
            }
        }
        return a;
    }

    // ------------------------------------------------------------------ exact search

    Result solve(long nodeBudget) {
        int[][] seed = greedy();
        bestAlloc = seed;
        bestObj = evaluate(seed).objective;
        remaining = new int[nFc][];
        for (int f = 0; f < nFc; f++) remaining[f] = d.inventory[f].clone();
        useCount = new int[nKeys];
        orderMax = new int[nOrders];
        cur = new int[nL][nFc + 1];
        nodes = 0;
        budget = nodeBudget;
        aborted = false;
        next(0, 0.0);
        return new Result(evaluate(bestAlloc), !aborted, nodes);
    }

    private void next(int li, double partial) {
        if (li == nL) {
            if (partial < bestObj - EPS) {
                bestObj = partial;
                bestAlloc = new int[nL][];
                for (int i = 0; i < nL; i++) bestAlloc[i] = cur[i].clone();
            }
            return;
        }
        assign(li, 0, qty[li], partial);
    }

    /** Distributes the units of line li over FC buckets b..nFc-1 (+ the unfulfilled bucket). */
    private void assign(int li, int b, int rem, double partial) {
        if (aborted) return;
        if (++nodes > budget) { aborted = true; return; }
        if (partial >= bestObj - EPS) return;           // bound: cannot beat the incumbent
        if (b == nFc) {                                  // leftover units cannot be served
            cur[li][nFc] = rem;
            next(li + 1, partial + UNFULFILLED_PENALTY * rem);
            cur[li][nFc] = 0;
            return;
        }
        int sku = skuIdx[li];
        int max = Math.min(rem, remaining[b][sku]);
        for (int u = max; u >= 0; u--) {
            double p = partial;
            int key = -1, oi = orderIdx[li], oldMax = orderMax[oi];
            if (u > 0) {
                key = keyId[li][b];
                if (useCount[key] == 0) p += keyCost[key];   // new package: pay shipping + handling once
                useCount[key]++;
                if (keyDays[key] > oldMax) {
                    p += lambda * (keyDays[key] - oldMax);
                    orderMax[oi] = keyDays[key];
                }
                remaining[b][sku] -= u;
            }
            cur[li][b] = u;
            assign(li, b + 1, rem - u, p);
            cur[li][b] = 0;
            if (u > 0) {
                remaining[b][sku] += u;
                orderMax[oi] = oldMax;
                useCount[key]--;
            }
        }
    }
}
