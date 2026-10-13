"""Run the explicit integration gate on fresh loopback Docker fixtures; never reuse user DBs.

Passwords live only in inherited process environments. Only verified created container IDs
are removed. Container identity and cleanup outcomes are printed without credentials.
"""
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import sys
import time
import uuid

sys.dont_write_bytecode = True

ROOT = Path(__file__).resolve().parents[2]
REPORTS = ROOT / "chat2db-community-server/chat2db-community-domain/chat2db-community-domain-core/target/surefire-reports"
FIXTURES = [("MYSQL", "mysql:8", 3306, "MYSQL_ROOT_PASSWORD", "root"),
            ("POSTGRES", "postgres:16", 5432, "POSTGRES_PASSWORD", "postgres"),
            ("MARIADB", "mariadb:11.4", 3306, "MARIADB_ROOT_PASSWORD", "root")]


def docker(*args, env=None):
    result = subprocess.run(["docker", *args], env=env, capture_output=True, text=True)
    if result.returncode:
        # Docker diagnostics may include environment arguments; never echo them wholesale.
        raise RuntimeError("Docker " + args[0] + " failed (exit " + str(result.returncode) + ")")
    return result.stdout.strip()


def cleanup_containers(containers, marker):
    errors = []
    for cid in reversed(containers):
        try:
            owner = docker("inspect", "--format", '{{index .Config.Labels "chat2db.it.owner"}}', cid)
            if owner != marker:
                raise RuntimeError("Refusing cleanup: fixture ownership mismatch")
            docker("rm", "--force", "--volumes", cid)
            print("REMOVED " + cid, flush=True)
        except Exception as failure:
            # A failed inspect/rm is not proof of absence. Require a successful daemon query
            # and an exact full-ID comparison; query errors remain cleanup failures.
            try:
                remaining = docker("ps", "--all", "--no-trunc", "--filter", "id=" + cid,
                                   "--format", "{{.ID}}").splitlines()
                absent = cid not in remaining
            except Exception:
                absent = False
            if absent:
                print("ALREADY_REMOVED " + cid, flush=True)
            else:
                errors.append(cid)
                print("CLEANUP_FAILED " + cid + ": " + str(failure), flush=True)
    return errors


def main():
    containers = []
    marker = "c2db-it-20261013-" + uuid.uuid4().hex[:10]
    env = os.environ.copy()
    env["C2D_IT_REQUIRED"] = "true"
    exit_code = 0
    try:
        for flavor, image, port, password_key, user in FIXTURES:
            password = secrets.token_hex(24)
            child_env = env.copy()
            child_env[password_key] = password
            name = marker + "-" + flavor.lower()
            cid = docker("run", "--detach", "--name", name, "--label", "chat2db.it.owner=" + marker,
                         "--publish", f"127.0.0.1::{port}", "--env", password_key, image, env=child_env)
            containers.append(cid)
            mapped = docker("port", cid, str(port) + "/tcp").split(":")[-1]
            print(f"CREATED {flavor} {cid} loopback-port={mapped} image={image}", flush=True)
            env.update({f"C2D_{flavor}_HOST": "127.0.0.1", f"C2D_{flavor}_PORT": mapped,
                        f"C2D_{flavor}_USER": user, f"C2D_{flavor}_PASSWORD": password,
                        f"C2D_{flavor}_DB": "c2d_task_it_" + flavor.lower()})
            deadline = time.monotonic() + 150
            while True:
                probe = ["pg_isready", "-U", "postgres"] if flavor == "POSTGRES" else [
                    "mariadb-admin" if flavor == "MARIADB" else "mysqladmin", "ping", "--silent"]
                ready = subprocess.run(["docker", "exec", cid, *probe], stdout=subprocess.DEVNULL,
                                       stderr=subprocess.DEVNULL).returncode == 0
                # Both MySQL images start a temporary socket-only bootstrap server first.
                if ready:
                    import socket
                    try:
                        with socket.create_connection(("127.0.0.1", int(mapped)), timeout=1) as sock:
                            if flavor == "POSTGRES" or sock.recv(1):
                                break
                    except OSError:
                        pass
                if time.monotonic() >= deadline:
                    raise RuntimeError(flavor + " fixture readiness timed out")
                time.sleep(1)
        # Remove only named prior reports, so stale XML cannot make an unexecuted gate pass.
        from importlib.machinery import SourceFileLoader
        verifier = SourceFileLoader("it_reports", str(ROOT / "script/test/verify-import-it-reports.py")).load_module()
        for name in verifier.EXPECTED:
            for old in REPORTS.glob("TEST-*." + name + ".xml"):
                old.unlink()
        command = [shutil.which("mvn") or "mvn", "-B", "-f", "chat2db-community-server/pom.xml",
                   "-pl", ":chat2db-community-domain-core", "-am", "-Dmaven.test.skip=false", "-DskipTests=false",
                   "-Dtest=" + ",".join(verifier.EXPECTED), "-Dsurefire.failIfNoSpecifiedTests=false",
                   "-Dmaven.test.failure.ignore=false", "test"]
        print("COMMAND " + " ".join(command), flush=True)
        result = subprocess.run(command, cwd=ROOT, env=env)
        if result.returncode:
            exit_code = result.returncode
            print("Maven exited with code " + str(exit_code), flush=True)
        else:
            verifier.verify(REPORTS)
    finally:
        cleanup_errors = cleanup_containers(containers, marker)
    # An active primary exception propagates after finally; cleanup never replaces it.
    return exit_code or (1 if cleanup_errors else 0)


if __name__ == "__main__":
    sys.exit(main())
