# Order Fulfillment Planner

Reads three JSON datasets (inventory, shipping, orders), decides which fulfillment center (FC) ships
what, respects finite inventory, and prints the plan plus metrics.

Maven project, pure Java with **no runtime dependencies** (includes a tiny JSON parser).
Needs **JDK 17+** (uses records) and **Maven 3.8+**. JUnit 5 is used for tests only.

## Build and run

```bash
cd fulfillment
mvn clean package                                   # compiles, runs tests, builds the jar
java -jar target/fulfillment-planner-1.0.0.jar data
```

Or run without packaging:

```bash
mvn -q compile exec:java -Dexec.args="data"
mvn -q compile exec:java -Dexec.args="data --lambda 10"
```

No Maven? The sources also build with plain `javac`:
`javac -d out src/main/java/fulfillment/*.java && java -cp out fulfillment.Main data`

Run the program from the project root so the relative `data` folder resolves (the tests do too).

Options (all optional):

| Flag | Default | Meaning |
|---|---|---|
| `<dataDir>` | `data` | Folder containing `inventory.json`, `shipping.json`, `orders.json` |
| `--lambda <usd>` | `2.0` | Dollar value of one order-day of delivery time (see "Cost vs. speed") |
| `--node-budget <n>` | `20000000` | Search cap; if hit, the best plan found is returned and flagged "not proven optimal" |

Try `--lambda 10` to see speed weighted more heavily.

## Files

```
pom.xml                                         Maven build (Java 17, JUnit 5, runnable jar)
data/inventory.json                             FC stock per SKU
data/shipping.json                              cost + delivery days per FC -> destination, handling fee
data/orders.json                                orders (customer, destination, items)
src/main/java/fulfillment/Json.java             minimal JSON parser
src/main/java/fulfillment/Model.java            data model + loader/validation
src/main/java/fulfillment/Planner.java          optimizer, greedy baseline, plan verifier
src/main/java/fulfillment/Main.java             CLI + report/metrics
src/test/java/fulfillment/PlannerTest.java      optimum, consolidation, inventory, lambda sensitivity
```

## How it works

**Decision:** for every (order, SKU) line, how many units come from each FC. Orders can be split.

**Shipment (package)** = (customer, destination, FC). Everything one customer receives at one
destination from one FC goes in a single package, so orders consolidate automatically whenever the
same FC serves them (rule 2). Each package pays `shipping + $3 handling` once.

**Inventory (rule 3)** is a hard constraint inside the search, and `Planner.verify()` re-checks every
final plan independently (all units accounted for, no SKU over-allocated at any FC).

**Search:** depth-first branch and bound over all ways to split each line across FCs. Every cost term
only grows as lines are added, so the partial objective is a valid lower bound and prunes aggressively
(331 nodes on the sample data). A greedy plan seeds the incumbent. The result is the **proven optimum**
unless the node budget is hit. If stock is insufficient, units are left unfulfilled with a $1,000/unit
penalty, so the plan fulfils as much as possible instead of failing.

## Cost vs. speed (rule 4)

```
objective = Σ packages (shipping + handling)  +  λ × Σ orders (order delivery days)
```

- **Order delivery days = the slowest package of that order**, because a customer experiences the order
  as delivered only when the last piece arrives. Splitting an order therefore never "hides" a slow leg.
- **λ = $2 per order-day (default).** Money is the primary goal; time is a meaningful tiebreaker. In this
  data, shipping prices differ by $1-$8 between FCs, so $2/day means a plan will not take a 3-day route to
  save $1, but will not pay $10 to save a day either. Using a single exchange rate keeps the trade-off
  explicit and tunable rather than hiding it in ad-hoc rules.
- **Sensitivity (verified):** at λ = 0 and λ = 2 the plan is the same ($31). At λ = 10 the planner
  un-consolidates Cust-1's CA orders (O2 from FC-West in 1 day instead of waiting 2 days for the
  consolidated package), paying $39 total to save one order-day. The flag lets the business pick its
  own point on that curve.

## Assumptions

- A shipment's shipping cost is per package, so consolidating two orders saves **both** one shipping
  charge and one handling charge (the dataset says costs are "per shipment").
- Consolidation requires the same customer, same destination and same FC (all of Cust-1's and Cust-2's
  orders in the sample share a destination).
- Package size/weight is not modelled: a package costs the same regardless of units.
- Each SKU line is fungible across FCs; partial shipments of one order are allowed.

## Sample output (default settings)

```
SHIPMENTS
#   From        To  Customer Orders     Ship  Hand  Total Days  Items
1   FC-West     TX  Cust-3   O4         9.00  3.00  12.00    2  O4:B x3, O4:C x1
2   FC-Central  CA  Cust-1   O1+O2      8.00  3.00  11.00    2  O1:A x2, O2:B x1
3   FC-East     NY  Cust-2   O3+O5      5.00  3.00   8.00    1  O3:A x1, O3:C x2, O5:A x3

METRICS
  Shipments (packages)        : 3  (5 without consolidation)
  TOTAL COST                  : $31.00   (shipping $22 + handling $9)
  Saved by consolidation      : $19.00
  Delivery days (order)       : avg 1.60, max 2
  Units fulfilled             : 13 of 13

BASELINE vs PLAN          Baseline     Plan
Shipments                        5        3
Total cost                  $47.00   $31.00
Avg delivery days             1.40     1.60
Objective                    61.00    47.00
```

The baseline is a per-unit greedy (cheapest/fastest in-stock FC per unit, no look-ahead). It is a
little faster on average but costs 52% more, because it ignores consolidation and picks FCs that
fragment into extra packages.

The full run also prints per-order delivery, FC/SKU inventory usage and cost-per-order/unit.
