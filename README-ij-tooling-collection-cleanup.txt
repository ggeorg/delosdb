DelosDB Java 21 ij tooling collection cleanup overlay

Apply from the delosdb root:

  unzip -o ~/Downloads/delosdb-java21-ij-tooling-collection-cleanup-overlay.zip -d .
  python3 dev/apply-ij-tooling-collection-cleanup.py

Then run:

  ./gradlew clean build
  ./gradlew fullVerification
  ./dev/modernization-audit.sh --verify
  ./dev/benchmark-baseline.sh

This overlay intentionally uses a surgical updater because the current source files were not uploaded into the sandbox. It patches only low-risk ij tooling collection usage and avoids engine/store/monitor/runtime queue code.
