Analyze the following supplier and news articles to determine potential
supply-chain risks.

================ SUPPLIER PROFILE ================

Supplier Name: {supplierName}
Location: {supplierLocation}
Goods Supplied: {goods}
Destination Market: {destination}
Transport Methods: {transport}

Use ONLY this profile to judge relevance. An article is relevant only if
it plausibly affects THIS supplier's specific goods, specific location,
specific destination route, or specific transport methods — not its
country or industry in general.
Never assume a specific port, airport, road, carrier, or logistics facility is used by the supplier unless it is explicitly stated in the supplier profile or article.
If the supplier's city and an affected logistics facility are the same, you may treat the event as potentially relevant, but do not claim that the facility is definitely part of the supplier's route.

================ NEWS ARTICLES ================

{rssJson}

================ TASK ================

You MUST analyze EVERY article and return exactly ONE assessment per
article, in the same order, using the exact articleId from the input.
Do not skip, merge, or omit any article.

For EACH article, work through these steps in order:

STEP 1 — Identify the mechanism (do this before scoring):
State, in one short phrase, the SPECIFIC causal link between the article's
event and this supplier's goods/location/destination/transport. Examples
of a valid mechanism: "port closure on the exact route used for
transport," "tariff on the exact goods category," "raw material shortage
for an input this supplier uses," "regulation directly restricting this
supplier's export destination."

If you cannot state a specific mechanism — only a general one like
"same country," "same broad industry," "economic news," "geopolitical
tension" with no concrete link to THIS supplier's goods/route — then
there is NO mechanism. This is a common and expected result, not a
failure. Most articles will have no mechanism. Go to Step 2 and score
MINIMAL/LOW.

STEP 2 — Score using this anchored scale:

- 0–19 MINIMAL: No mechanism identified, or only a coincidental keyword
  overlap (e.g., same country name, same generic industry word) with no
  operational link.
- 20–39 LOW: A mechanism exists but is weak, indirect, or several steps
  removed (e.g., affects a neighboring industry that might eventually
  affect input costs).
- 40–59 MODERATE: A direct mechanism exists and could cause real but
  limited disruption — added cost, minor delay, uncertainty.
- 60–79 HIGH: A direct mechanism exists with strong potential for
  significant disruption — meaningful delays, shortages, restricted
  access, cost spikes.
- 80–100 CRITICAL: A direct mechanism poses a severe, near-term threat
  to this supplier's actual operations or shipments.

Calibration examples (do not copy verbatim, these illustrate judgment):
- Supplier: Chinese PCB manufacturer, ships to US via sea freight.
  Article: "Canada-US trade war intensifies" → NO mechanism (wrong
  country, wrong goods, wrong route) → MINIMAL (0-10).
- Same supplier. Article: "China tightens export rules on agricultural
  goods" → NO mechanism (different goods category) → MINIMAL (0-10).
- Same supplier. Article: "US announces new tariffs on Chinese
  electronics components" → direct mechanism (same goods, same route)
  → HIGH or CRITICAL depending on severity described.
- Same supplier. Article: "Congestion reported at major Chinese export
  ports" → mechanism exists if the port is plausibly on this supplier's
  shipping route → MODERATE to HIGH depending on specificity.

STEP 3 — Assign risk level from score:
- 0–39 → LOW
- 40–59 → MEDIUM
- 60–100 → HIGH

STEP 4 — Write ONE concise sentence as "reason" that explicitly names
the mechanism (or explicitly states "no direct mechanism to this
supplier's goods/route" if scoring MINIMAL/LOW).

================ SELF-CHECK BEFORE RESPONDING ================

1. Every input article has exactly one assessment, same order, same ID.
2. No article has been skipped, merged, or duplicated.
3. Every "reason" names a concrete mechanism OR explicitly says none
   exists — no vague reasoning like "trade war intensifying" without
   tying it to this supplier's actual goods/route.
4. You have NOT scored anything above LOW purely because a country,
   city, or generic industry word matched. If you catch yourself doing
   this, revise the score down.


