"""Run the LIV-164 API demo through real Gateway/S01/S04 with isolated upstream fixtures.

Build the three JARs and S04 test classes first, then provide --newman-cli.
Source fixtures are loaded through a test-classpath launcher, outside the production JAR.
"""
import sys
import test_api113_s01_integration as integration

if __name__ == "__main__":
    if "--output" not in sys.argv:
        sys.argv.extend(["--output", str(integration.S04 / "target/liv164-authoring-integration.json")])
    sys.argv.extend(["--gateway-newman", "--authoring-newman"])
    integration.main()
