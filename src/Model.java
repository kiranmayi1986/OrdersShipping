import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Input data model plus the loader that reads the three JSON datasets. */
final class Model {
    private Model() {}

    /** One (order, SKU) demand line. */
    record Line(String order, String customer, String shipTo, String sku, int qty) {}

    /** Shipping cost (excluding handling) and transit days for one FC -> destination pair. */
    record Route(double cost, int days) {}

    static final class Data {
        final List<String> skus;
        final List<String> fcs;
        final List<String> fcLocations;
        final int[][] inventory;                 // [fc][sku]
        final Map<String, Route[]> routes;       // ship_to -> route per FC (same index as fcs)
        final double handling;
        final List<Line> lines;
        final List<String> orders;               // order ids in file order

        Data(List<String> skus, List<String> fcs, List<String> fcLocations, int[][] inventory,
             Map<String, Route[]> routes, double handling, List<Line> lines, List<String> orders) {
            this.skus = skus;
            this.fcs = fcs;
            this.fcLocations = fcLocations;
            this.inventory = inventory;
            this.routes = routes;
            this.handling = handling;
            this.lines = lines;
            this.orders = orders;
        }

        String customerOf(String order) {
            for (Line l : lines) if (l.order().equals(order)) return l.customer();
            return "?";
        }

        String destOf(String order) {
            for (Line l : lines) if (l.order().equals(order)) return l.shipTo();
            return "?";
        }
    }

    static Data load(Path dir) throws IOException {
        // ---- inventory.json ----
        Map<String, Object> inv = Json.obj(read(dir, "inventory.json"), "inventory.json");
        List<String> skus = new ArrayList<>();
        for (Object o : Json.list(inv.get("skus"), "inventory.json: skus")) {
            String sku = Json.str(o, "inventory.json: skus[]");
            if (skus.contains(sku)) throw new IllegalArgumentException("inventory.json: duplicate SKU " + sku);
            skus.add(sku);
        }
        List<Object> fcList = Json.list(inv.get("fulfillment_centers"), "inventory.json: fulfillment_centers");
        List<String> fcs = new ArrayList<>();
        List<String> locations = new ArrayList<>();
        int[][] inventory = new int[fcList.size()][skus.size()];
        for (int f = 0; f < fcList.size(); f++) {
            Map<String, Object> fc = Json.obj(fcList.get(f), "inventory.json: fulfillment_centers[" + f + "]");
            String name = Json.str(fc.get("fc"), "inventory.json: fc name");
            if (fcs.contains(name)) throw new IllegalArgumentException("inventory.json: duplicate FC " + name);
            fcs.add(name);
            locations.add(fc.get("location") instanceof String ? (String) fc.get("location") : "?");
            Map<String, Object> stock = Json.obj(fc.get("inventory"), "inventory.json: " + name + " inventory");
            for (Map.Entry<String, Object> e : stock.entrySet()) {
                int si = skus.indexOf(e.getKey());
                if (si < 0) throw new IllegalArgumentException("inventory.json: " + name + " stocks unknown SKU " + e.getKey());
                inventory[f][si] = Json.nonNegInt(e.getValue(), "inventory.json: " + name + "/" + e.getKey());
            }
        }

        // ---- shipping.json ----
        Map<String, Object> ship = Json.obj(read(dir, "shipping.json"), "shipping.json");
        double handling = ship.containsKey("handling_fee") ? Json.num(ship.get("handling_fee"), "shipping.json: handling_fee") : 3.0;
        Map<String, Route[]> routes = new LinkedHashMap<>();
        for (Object o : Json.list(ship.get("shipping"), "shipping.json: shipping")) {
            Map<String, Object> entry = Json.obj(o, "shipping.json: shipping[]");
            String dest = Json.str(entry.get("ship_to"), "shipping.json: ship_to");
            Route[] r = new Route[fcs.size()];
            Map<String, Object> options = Json.obj(entry.get("options"), "shipping.json: " + dest + " options");
            for (Map.Entry<String, Object> e : options.entrySet()) {
                int f = fcs.indexOf(e.getKey());
                if (f < 0) throw new IllegalArgumentException("shipping.json: unknown FC " + e.getKey() + " for " + dest);
                Map<String, Object> rv = Json.obj(e.getValue(), "shipping.json: " + dest + "/" + e.getKey());
                double cost = Json.num(rv.get("cost"), "shipping.json: " + dest + "/" + e.getKey() + " cost");
                if (cost < 0) throw new IllegalArgumentException("shipping.json: negative cost for " + dest + "/" + e.getKey());
                r[f] = new Route(cost, Json.nonNegInt(rv.get("delivery_days"), "shipping.json: " + dest + "/" + e.getKey() + " delivery_days"));
            }
            for (int f = 0; f < r.length; f++) {
                if (r[f] == null) throw new IllegalArgumentException("shipping.json: no route from " + fcs.get(f) + " to " + dest);
            }
            routes.put(dest, r);
        }

        // ---- orders.json ----
        Map<String, Object> ord = Json.obj(read(dir, "orders.json"), "orders.json");
        List<String> orderIds = new ArrayList<>();
        Map<String, Line> merged = new LinkedHashMap<>();   // (order|sku) -> line; merges repeated SKUs in one order
        for (Object o : Json.list(ord.get("orders"), "orders.json: orders")) {
            Map<String, Object> order = Json.obj(o, "orders.json: orders[]");
            String id = Json.str(order.get("order"), "orders.json: order id");
            if (orderIds.contains(id)) throw new IllegalArgumentException("orders.json: duplicate order " + id);
            orderIds.add(id);
            String customer = Json.str(order.get("customer"), "orders.json: " + id + " customer");
            String dest = Json.str(order.get("ship_to"), "orders.json: " + id + " ship_to");
            if (!routes.containsKey(dest)) throw new IllegalArgumentException("orders.json: " + id + " ships to " + dest + " which has no shipping data");
            for (Object it : Json.list(order.get("items"), "orders.json: " + id + " items")) {
                Map<String, Object> item = Json.obj(it, "orders.json: " + id + " item");
                String sku = Json.str(item.get("sku"), "orders.json: " + id + " sku");
                if (!skus.contains(sku)) throw new IllegalArgumentException("orders.json: " + id + " uses unknown SKU " + sku);
                int q = Json.nonNegInt(item.get("quantity"), "orders.json: " + id + "/" + sku + " quantity");
                if (q == 0) continue;
                String key = id + "|" + sku;
                Line prev = merged.get(key);
                merged.put(key, new Line(id, customer, dest, sku, q + (prev == null ? 0 : prev.qty())));
            }
        }
        return new Data(skus, fcs, locations, inventory, routes, handling, new ArrayList<>(merged.values()), orderIds);
    }

    private static Object read(Path dir, String name) throws IOException {
        Path p = dir.resolve(name);
        if (!Files.isReadable(p)) throw new IOException("Cannot read " + p.toAbsolutePath());
        return Json.parse(Files.readString(p));
    }
}
