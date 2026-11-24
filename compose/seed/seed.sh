#!/bin/sh
# Seeds demo data into the running API and prints example searches.
# BASE can be overridden for JVM dev mode: BASE=http://localhost:8080 ...
set -eu

BASE="${BASE:-http://app:8080}"

until curl -sf "$BASE/q/health/ready" >/dev/null 2>&1; do
  echo "waiting for the API..."
  sleep 2
done

post() {
  curl -sf -X POST -H 'Content-Type: application/json' -d "$2" "$BASE$1"
}

# Extracts the "id" field from a JSON body (id is the first field).
json_id() {
  sed -E 's/.*"id":"([^"]+)".*/\1/'
}

wait_indexed() {
  client_id=$1
  doc_id=$2
  i=0
  while [ $i -lt 40 ]; do
    status=$(curl -sf "$BASE/clients/$client_id/documents/$doc_id" | sed -E 's/.*"index_status":"([^"]+)".*/\1/')
    if [ "$status" = "indexed" ]; then
      return 0
    fi
    i=$((i + 1))
    sleep 3
  done
  echo "WARNING: document $doc_id is still '$status' after polling; continuing anyway" >&2
  return 1
}

echo "=== Creating demo clients ==="
JOHN_ID=$(post /clients '{
  "first_name": "John",
  "last_name": "Doe",
  "email": "john.doe@neviswealth.com",
  "description": "Senior wealth advisor at Nevis Wealth management, focuses on retirement portfolios",
  "social_links": ["https://linkedin.com/in/john-doe"]
}' | json_id)
echo "John Doe ($JOHN_ID)"

ALICE_ID=$(post /clients '{
  "first_name": "Alice",
  "last_name": "Smith",
  "email": "alice.smith@acmecapital.com",
  "description": "Retirement planning specialist"
}' | json_id)
echo "Alice Smith ($ALICE_ID)"

BOB_ID=$(post /clients '{
  "first_name": "Bob",
  "last_name": "Marley",
  "email": "bob.marley@trenchtown.jm",
  "description": "Music royalty income advisory"
}' | json_id)
echo "Bob Marley ($BOB_ID)"

echo ""
echo "=== Creating demo documents ==="
DOC1=$(post "/clients/$JOHN_ID/documents" '{
  "title": "Utility bill for 42 Baker Street",
  "content": "Monthly electricity utility bill issued for the property where the customer lives. Utility bills like this one are commonly requested to verify where a person lives and are accepted as a household document for verification."
}' | json_id)
DOC2=$(post "/clients/$JOHN_ID/documents" '{
  "title": "Passport scan",
  "content": "Certified scan of the biometric passport: identity page with photo, passport number, issue and expiry dates, issuing authority."
}' | json_id)
DOC3=$(post "/clients/$JOHN_ID/documents" '{
  "title": "Bank statement Q1",
  "content": "Quarterly bank statement listing account balances, dividend income and regular salary deposits."
}' | json_id)
DOC4=$(post "/clients/$ALICE_ID/documents" '{
  "title": "Employment contract",
  "content": "Signed employment contract with salary terms, bonus structure and notice period."
}' | json_id)
DOC5=$(post "/clients/$BOB_ID/documents" '{
  "title": "Royalty statement",
  "content": "Annual music royalty statement with streaming, publishing and performance income breakdown."
}' | json_id)

echo "Waiting for the index worker to embed and summarize all documents..."
for doc in "$DOC1" "$DOC2" "$DOC3" "$DOC4" "$DOC5"; do
  case "$doc" in
    "$DOC1") owner=$JOHN_ID ;;
    "$DOC2") owner=$JOHN_ID ;;
    "$DOC3") owner=$JOHN_ID ;;
    "$DOC4") owner=$ALICE_ID ;;
    "$DOC5") owner=$BOB_ID ;;
  esac
  wait_indexed "$owner" "$doc" || true
done

echo ""
echo "=== Example 1: search 'NevisWealth' (client by email/name/description) ==="
curl -sf "$BASE/search?q=NevisWealth"
echo ""
echo ""
echo "=== Example 2: search 'address proof' (documents by similar terms) ==="
curl -sf "$BASE/search?q=address%20proof"
echo ""
echo ""
echo "=== Example 3: search 'Jonh Doe' (typo, fuzzy name match) ==="
curl -sf "$BASE/search?q=Jonh%20Doe"
echo ""
echo ""
echo "=== Example 4: search 'bank statement' with type=document ==="
curl -sf "$BASE/search?q=bank%20statement&type=document"
echo ""
echo ""
echo "=== Documents of John Doe (with generated summaries) ==="
curl -sf "$BASE/clients/$JOHN_ID/documents"
echo ""
echo ""
echo "Seeding done. API: http://localhost:8080  Swagger UI: http://localhost:8080/q/swagger-ui"
