You are an expert supply-chain intelligence analyst.

Your task is to generate exactly 10 CURRENT news-search queries that could identify real events capable of disrupting this supplier's ability to manufacture or deliver its products.

SUPPLIER PROFILE
Supplier: Shenzhen Semiconductor Components Ltd.
Location: Shenzhen, Guangdong, China
Goods:
- semiconductor chips
- microcontrollers
- PCB components

Destination: Bangalore, India
Transport:
- sea
- road

IMPORTANT:
The supplier is a hypothetical company. Do NOT assume that any specific factory, port, road, supplier, customer, shipping company, or facility belongs to this company unless explicitly stated above.

Generate queries covering a balanced mixture of:

1. Direct manufacturing disruptions in Shenzhen
2. Semiconductor/component factory shutdowns in Shenzhen
3. Port and maritime disruptions relevant to Shenzhen exports
4. Trucking/road disruptions affecting Shenzhen freight
5. Weather/natural disasters affecting Shenzhen or Guangdong
6. China → India trade/export restrictions affecting semiconductor/electronic components
7. Semiconductor/electronics industry disruptions that could affect component availability
8. Shipping disruptions between China and India
9. Customs/import restrictions affecting semiconductor/electronic components entering India
10. Major geopolitical events that could realistically disrupt China → India component supply

DIFFICULTY REQUIREMENTS:

- Queries must target CURRENT or RECENT events.
- Do not insert arbitrary years.
- Do not fabricate specific roads, ports, factories, shipping routes, companies, strikes, or government policies.
- Do not assume that every event in Shenzhen affects this supplier.
- Avoid queries that are so broad that they return generic industry news.
- Avoid queries that are so specific that they require a fact that has not been provided.
- Keep each query focused on ONE risk.
- Do not combine weather + port + trade restrictions into one query.
- Use geographic context where useful.
- Use product context where useful.
- Prefer queries that would produce actionable supply-chain intelligence rather than general news.
- Include both direct and indirect disruption scenarios.
- At least 3 queries should test indirect risks where the connection to the supplier must be inferred from the resulting article.
- At least 2 queries should distinguish between disruption to manufacturing and disruption to transportation.
- Do not repeat the same query structure with only the location changed.
- Do not generate hypothetical events. Search for real events that may currently be happening.

OUTPUT REQUIREMENTS:
Return a JSON object matching this Java record structure:
{
  "queries": [
    {"query": "search query 1"},
    {"query": "search query 2"}
  ]
}

IMPORTANT:
- "queries" is an array of Query records.
- Each Query record has exactly one field: "query".
- Do NOT return strings directly inside "queries".
- Return ONLY valid JSON.
- No markdown or explanation.

Return ONLY valid JSON.
No markdown.
No explanation.
No additional fields.

