name: Engine tests
on:
  push:
  workflow_dispatch:
jobs:
  engine:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: '17' }
      - uses: actions/setup-python@v5
        with: { python-version: '3.12' }
      - name: Generate independent Python reference data
        run: |
          cd tools/xcheck && python3 ref.py && mkdir -p out && mv bars.csv ref.csv out/
      - uses: gradle/actions/setup-gradle@v4
        with: { gradle-version: '8.10.2' }
      - name: Run engine self-tests (fails the job on any failed check)
        run: gradle :engine:selfTest --no-daemon
