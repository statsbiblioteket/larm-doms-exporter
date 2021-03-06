#!/bin/bash

# Takes a list of recordIDs and extracts the full record tree

# Requirements: bash, xmllint, curl, sed

###############################################################################
# CONFIG
###############################################################################

pushd ${BASH_SOURCE%/*} > /dev/null
if [[ -s summarise.conf ]]; then
    source summarise.conf
fi
: ${SUMMA_STORAGE:="http://mars.statsbiblioteket.dk:57308/doms/storage/services/StorageWS"}

: ${SOURCE:="$1"}

: ${OUT_FOLDER:="$2"}
: ${OUT_FOLDER:="records_$(date +%Y%m%d-%H%M%S)"} # This will be a folder

# Note: EXPAND currently has no effect
: ${EXPAND:="true"} # true = expand parent/child, false = single-level records
: ${FORCE:="false"} # If true, already fetched files are overwritten
popd > /dev/null

function usage() {
    cat <<EOF

Usage:  ./get_records.sh record_file [destination_folder]

record_file:        File with the recordIDs to fetch, one id/line
destination_folder: Where to store the fetched records

See the CONFIG section of this script for extra parameters
EOF
    exit $1
}

check_parameters() {
    if [[ -z "$SOURCE" ]]; then
        >&2 echo "Error: No source file specified"
        usage 2
    fi
}

################################################################################
# FUNCTIONS
################################################################################

get_record() {
    local RECORD_ID="$1"
    local OUT_NAME=$(sed 's/[^a-zA-Z_0-9.]/_/g' <<< "$RECORD_ID").xml
    if [[ "false" == "$FORCE" && -s "$OUT_FOLDER/$OUT_NAME" ]]; then
        echo " - Skipping $RECORD_ID as is has already been fetched"
        return
    fi
    echo " - Fetching $OUT_FOLDER/$OUT_NAME"
    if [[ "true" == "$EXPAND" ]]; then
        # expand is ignored
        curl -s -G "$SUMMA_STORAGE?method=getCustomRecord&expand=false&legacyMerge=false&escapeContent=false&" --data-urlencode "id=${RECORD_ID}" | sed -e 's/<soapenv.*getCustomRecordReturn[^>]*>//' -e 's/<\/ns1:getCustomRecordReturn.*//' -e 's/&lt;/</g' -e 's/&gt;/>/g' -e 's/&quot;/"/g' -e 's/&amp;/&/g' | xmllint --format - > "$OUT_FOLDER/$OUT_NAME"
    else
        >&2 echo "EXPAND=false not supported yet"
        exit 4
    fi
}

get_records() {
    mkdir -p "$OUT_FOLDER"
    while read -r RECORD_ID; do
        if [[ "." == ".$RECORD_ID" ]]; then
            continue
        fi
        get_record "$RECORD_ID"
    done < "$SOURCE"
}

###############################################################################
# CODE
###############################################################################

check_parameters "$@"
get_records
echo "Finished extracting $(wc -l < "$SOURCE") records, result stored in folder $OUT_FOLDER"
