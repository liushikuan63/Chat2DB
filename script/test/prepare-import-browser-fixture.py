"""Create only a named loopback MySQL browser fixture; keep credentials in memory."""
import argparse
import json
import os
from pathlib import Path
import secrets
import subprocess
import time
import urllib.parse
import urllib.request
import uuid

DATABASE = "c2d_ui_fixture_20261013"
LABEL = "chat2db.browser.owner"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--backend-url", default="http://127.0.0.1:10825")
    parser.add_argument("--output", default="target/import-browser-evidence/fixture.json")
    parser.add_argument("--cleanup", metavar="FIXTURE_JSON")
    parser.add_argument("--driver-name", default="mysql-ui-fixture-8.3.0.jar")
    args = parser.parse_args()
    origin = urllib.parse.urlsplit(args.backend_url)
    if origin.scheme != "http" or origin.hostname not in {"127.0.0.1", "localhost", "::1"} or origin.username or origin.password:
        raise ValueError("A credential-free loopback backend is required")
    if Path(args.driver_name).name != args.driver_name:
        raise ValueError("Driver name must be a basename already prepared in the isolated backend home")
    env = os.environ.copy()

    def docker(*values):
        result = subprocess.run(["docker", *values], env=env, capture_output=True, text=True)
        if result.returncode:
            # Do not echo stderr, arguments or container environment: they may contain credentials.
            raise RuntimeError("Docker " + values[0] + " failed, exit=" + str(result.returncode))
        return result.stdout.strip()

    def remove_owned(receipt):
        if receipt.get("database") != DATABASE or not receipt.get("marker", "").startswith("c2db-browser-"):
            raise ValueError("Unexpected fixture ownership")
        cid = receipt["containerId"]
        if len(cid) != 64 or any(char not in "0123456789abcdef" for char in cid):
            raise ValueError("Expected the full recorded container ID")
        owner = docker("inspect", "--format", '{{index .Config.Labels "' + LABEL + '"}}', cid)
        if owner != receipt["marker"]:
            raise ValueError("Container owner label changed; refusing cleanup")
        docker("rm", "--force", cid)
        print("REMOVED " + cid)

    if args.cleanup:
        remove_owned(json.loads(Path(args.cleanup).read_text(encoding="utf8")))
        return

    # Caller must have started the Community backend with an explicitly isolated user.home.
    def api(path, body=None):
        request = urllib.request.Request(args.backend_url.rstrip("/") + path,
            data=None if body is None else json.dumps(body).encode(),
            headers={"Content-Type": "application/json"})
        result = json.load(urllib.request.urlopen(request, timeout=30))
        if not result.get("success"):
            raise RuntimeError("Fixture API request failed: " + str(result.get("errorCode")))
        return result.get("data")

    existing = api("/api/connection/datasource/list?pageNo=1&pageSize=100")
    if any(item.get("alias") == "C2D_BROWSER_MYSQL" for item in (existing or {}).get("data", [])):
        raise RuntimeError("C2D_BROWSER_MYSQL already exists; use its recorded fixture instead of duplicating it")
    password = secrets.token_hex(24)
    env["MYSQL_ROOT_PASSWORD"] = password
    env["MYSQL_PWD"] = password
    marker = "c2db-browser-" + uuid.uuid4().hex
    cid = docker("run", "--detach", "--name", marker, "--label", LABEL + "=" + marker,
        "--publish", "127.0.0.1::3306", "--env", "MYSQL_ROOT_PASSWORD", "mysql:8")
    receipt = {"containerId": cid, "marker": marker, "host": "127.0.0.1",
        "database": DATABASE, "image": "mysql:8"}
    output = Path(args.output)
    try:
        receipt["port"] = int(docker("port", cid, "3306/tcp").split(":")[-1])
        deadline = time.monotonic() + 150
        while True:
            probe = subprocess.run(["docker", "exec", "--env", "MYSQL_PWD", cid, "mysql", "-uroot",
                "--protocol=TCP", "-h127.0.0.1", "-e", "SELECT 1"], env=env,
                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            if probe.returncode == 0:
                break
            if time.monotonic() >= deadline:
                raise RuntimeError("Fixture did not become ready within 150 seconds")
            time.sleep(1)
        ddl = """CREATE DATABASE c2d_ui_fixture_20261013 CHARACTER SET utf8mb4;
USE c2d_ui_fixture_20261013;
CREATE TABLE C2D_UI_SINGLE (ID INT PRIMARY KEY, NOTE VARCHAR(255), QTY DECIMAL(16,2));
CREATE TABLE C2D_UI_BAD (ID INT PRIMARY KEY, NOTE VARCHAR(255), QTY DECIMAL(16,2));
CREATE TABLE C2D_UI_PARENT (ID INT PRIMARY KEY, NOTE VARCHAR(255));
CREATE TABLE C2D_UI_CHILD (ID INT PRIMARY KEY, PARENT_ID INT,
 NOTE VARCHAR(255), FOREIGN KEY (PARENT_ID) REFERENCES C2D_UI_PARENT(ID));"""
        docker("exec", "--env", "MYSQL_PWD", cid, "mysql", "-uroot", "--protocol=TCP", "-h127.0.0.1", "-e", ddl)
        result = api("/api/connection/datasource/create", {
            "alias": "C2D_BROWSER_MYSQL", "type": "MYSQL", "environmentId": 2,
            "url": "jdbc:mysql://127.0.0.1:" + str(receipt["port"]) + "/" + DATABASE +
                   "?allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=UTC",
            "user": "root", "password": password,
            "driverConfig": {"jdbcDriver": args.driver_name, "jdbcDriverClass": "com.mysql.cj.jdbc.Driver",
                "dbType": "MYSQL", "defaultDriver": True, "custom": False, "downloadJdbcDriverUrls": []}})
        receipt["dataSourceId"] = result["id"]
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(receipt, indent=2), encoding="utf8")
        print("CREATED " + cid + "; fixture receipt: " + str(output))
    except BaseException:
        try:
            remove_owned(receipt)
        except Exception as cleanup_error:
            # Retain ownership for a later explicit cleanup, without persisting the password.
            output.parent.mkdir(parents=True, exist_ok=True)
            output.write_text(json.dumps(receipt, indent=2), encoding="utf8")
            print("Fixture cleanup failed: " + type(cleanup_error).__name__ + "; receipt: " + str(output))
        raise


if __name__ == "__main__":
    main()
