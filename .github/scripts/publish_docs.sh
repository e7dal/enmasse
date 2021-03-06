#!/bin/bash
set -e

DOCVERSION=master
if [ "${RELEASE}" == "true" ]; then
    DOCVERSION=${TAG}
fi

echo "Publishing docs for version ${DOCVERSION}"

git clone https://${CI_TOKEN}@github.com/EnMasseProject/enmasseproject.github.io.git ${TO}

rm -rf  ${TO}/documentation/${DOCVERSION}/images
cp -vrL ${FROM}/documentation/target/generated-docs/htmlnoheader/* ${TO}/documentation/${DOCVERSION}

pushd ${TO}
if [[ -z $(git status -s) ]]; then
    echo "No changes to the output on this push; exiting."
    exit 0
fi

git config user.name "EnMasse CI"
git config user.email "enmasse-ci@redhat.com"

git add -A
git commit -s -m "Update documentation from master" --allow-empty

git push origin master

popd

