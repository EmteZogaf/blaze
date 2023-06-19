#!/bin/bash -e

SCRIPT_DIR="$(dirname "$(readlink -f "$0")")"
. "$SCRIPT_DIR/util.sh"

BASE="http://localhost:8080/fhir"
CAPABILITY_STATEMENT=$(curl -sH 'Accept: application/fhir+json' "$BASE/metadata")

test "software name" "$(echo "$CAPABILITY_STATEMENT" | jq -r .software.name)" "Blaze"
test "URL" "$(echo "$CAPABILITY_STATEMENT" | jq -r .implementation.url)" "http://localhost:8080/fhir"

test "Operation Measure \$evaluate-measure Definition" "$(echo "$CAPABILITY_STATEMENT" | jq -r '.rest[0].resource[] | select(.type == "Measure") .operation[] | select(.name == "evaluate-measure") | .definition')" "http://hl7.org/fhir/OperationDefinition/Measure-evaluate-measure"
test "Operation Patient \$everything Definition" "$(echo "$CAPABILITY_STATEMENT" | jq -r '.rest[0].resource[] | select(.type == "Patient") .operation[] | select(.name == "everything") | .definition')" "http://hl7.org/fhir/OperationDefinition/Patient-everything"
test "Operation Patient \$everything Documentation" "$(echo "$CAPABILITY_STATEMENT" | jq -r '.rest[0].resource[] | select(.type == "Patient") .operation[] | select(.name == "everything") | .documentation')" "Returns all resources from the patient compartment of one concrete patient including the patient. Has a fix limit of 10,000 resources. Paging ist not supported. No params are supported."
