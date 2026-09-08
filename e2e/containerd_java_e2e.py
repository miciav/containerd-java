"""End-to-end scenario for containerd-java and libcni-java, driven by sonata-engine.

Creates a Multipass VM, installs containerd and crun to run rootless, deploys the demo
application built from this checkout, runs it against that containerd, and reports which
scenarios held.

Why a VM and not the developer's machine: the libraries' claims are about a containerd they do
not control, and the interesting half of those claims -- rootless containers, CNI attachment into
RootlessKit's network namespace, a runtime that is crun rather than runc -- cannot be tested on a
host that already has containerd set up the way its owner likes it. A machine created for the run
and destroyed after it is the only way to know the result came from the libraries and not from
something the host happened to have.

Run with:
    uv venv .venv-e2e
    uv pip install --python .venv-e2e /path/to/sonata \
        "/path/to/sonata/packages/sonata-tasks[multipass]"
    .venv-e2e/bin/python e2e/containerd_java_e2e.py

The multipass extra is what supplies the Multipass SDK and pydantic; without it the import of
sonata_tasks.vm.models fails. See docs/end-to-end.md for the environment variables, and for
keeping the VM with KEEP_VM=1, which skips the release and leaves cleanup to you.
"""

from __future__ import annotations

import json
import os
import re
import shlex
import subprocess
import sys
import time
from contextlib import contextmanager
from pathlib import Path
from typing import Generator

from sonata_engine import (
    Resource,
    Task,
    TaskInputs,
    TaskOutcome,
    Workflow,
    WorkflowEvent,
    bind_workflow_sink,
    workflow_log,
)
from sonata_tasks.vm.models import VmRequest
from sonata_tasks.vm.providers.multipass import MultipassVmProvider

REPO = Path(__file__).resolve().parent.parent
VM_NAME = os.environ.get("E2E_VM_NAME", "cjava-workflow")
# Pinned rather than "latest": a scenario that reports what the libraries do has to run against a
# stated containerd, or a failure cannot be told apart from an upstream change.
NERDCTL_VERSION = "2.3.5"
NETWORK = "cjava-e2e"
REMOTE_HOME = "/home/ubuntu"
SSH_READY_TIMEOUT = 300

# The demo's own network. nerdctl's default bridge declares no DNS, so a demo run against it
# reports an empty resolv.conf and the DNS half of the networking scenario proves nothing. This
# one names servers and a domain, which is what makes that scenario a test.
NETWORK_CONFLIST = {
    "cniVersion": "1.0.0",
    "name": NETWORK,
    "plugins": [
        {
            "type": "bridge",
            "bridge": "cjava0",
            "isGateway": True,
            "ipMasq": True,
            "ipam": {
                "type": "host-local",
                "ranges": [[{"subnet": "10.90.0.0/24"}]],
                "routes": [{"dst": "0.0.0.0/0"}],
            },
            "dns": {
                "nameservers": ["10.90.0.1", "1.1.1.1"],
                "domain": "cjava.local",
                "search": ["cjava.local"],
            },
        },
        {"type": "firewall"},
        {"type": "loopback"},
    ],
}

PROVISION = f"""
set -eux
export DEBIAN_FRONTEND=noninteractive
sudo apt-get update -qq
# crun is the runtime under test; uidmap and dbus-user-session are what let containerd run
# rootless at all; the JRE runs the demo.
sudo apt-get install -y -qq crun uidmap dbus-user-session openjdk-25-jre-headless curl
# The architecture is the VM's to report, not this machine's to assume: the bundle runs there.
# dpkg names it the same way nerdctl's release assets do (amd64, arm64), so no mapping is needed.
ARCH=$(dpkg --print-architecture)
curl -fsSL -o /tmp/nerdctl-full.tgz \
  https://github.com/containerd/nerdctl/releases/download/v{NERDCTL_VERSION}/nerdctl-full-{NERDCTL_VERSION}-linux-$ARCH.tar.gz
sudo tar Cxzf /usr/local /tmp/nerdctl-full.tgz
# Ubuntu confines unprivileged user namespaces with AppArmor, and RootlessKit needs one: without
# this profile the setup below fails with "fork/exec /proc/self/exe: permission denied", which
# says nothing about the actual cause. This is the distribution's own documented remedy.
cat <<'PROFILE' | sudo tee /etc/apparmor.d/usr.local.bin.rootlesskit >/dev/null
abi <abi/4.0>,
include <tunables/global>

/usr/local/bin/rootlesskit flags=(unconfined) {{
  userns,
  include if exists <local/usr.local.bin.rootlesskit>
}}
PROFILE
sudo systemctl restart apparmor.service
# Without lingering the user's systemd instance dies with the SSH session, taking rootless
# containerd with it, and the next task would find a socket that was there a moment ago.
sudo loginctl enable-linger ubuntu
containerd-rootless-setuptool.sh install
"""


class ConsoleSink:
    """Prints what the workflow is doing. Without a sink bound, nothing is reported."""

    _MARKS = {"task.started": "->", "task.passed": "ok", "task.failed": "XX"}

    def emit(self, event: WorkflowEvent) -> None:
        if event.kind == "log.line":
            print(f"       . {event.line}", flush=True)
            return
        mark = self._MARKS.get(event.kind)
        if mark:
            print(f"  {mark} {event.task_id}", flush=True)

    @contextmanager
    def status(self, label: str) -> Generator[None, None, None]:
        yield


def _provider() -> MultipassVmProvider:
    return MultipassVmProvider(workspace_root=REPO)


def _request() -> VmRequest:
    return VmRequest(lifecycle="multipass", name=VM_NAME, cpus=4, memory="6G", disk="20G")


def _ssh(script: str) -> str:
    """Runs a script in the VM, raising with its output if it fails.

    The provider's own result object reports failure in a field; raising instead is what makes a
    failed provisioning step fail its task rather than let the next one run against a half-built
    machine.
    """
    result = _provider().remote_exec(_request(), command=script)
    if result.return_code != 0:
        raise RuntimeError(
            f"remote command failed ({result.return_code}):\n{result.stdout}\n{result.stderr}"
        )
    return result.stdout


def _acquire_vm(inputs: TaskInputs) -> str:
    provider, request = _provider(), _request()
    provider.ensure_running(request)
    host = provider.connection_host(request)
    # multipass reports the VM running before sshd will answer, and cloud-init may still be
    # installing the key. Polling for the thing actually needed beats sleeping a guessed interval.
    deadline = time.monotonic() + SSH_READY_TIMEOUT
    while time.monotonic() < deadline:
        if provider.remote_exec(request, command="true").return_code == 0:
            workflow_log(f"{VM_NAME} reachable at {host}")
            return host
        time.sleep(5)
    raise RuntimeError(f"{VM_NAME} did not accept ssh within {SSH_READY_TIMEOUT}s")


def _release_vm(inputs: TaskInputs, host: str) -> None:
    _provider().teardown(_request())


vm: Resource[str] = Resource(
    title="Multipass VM",
    acquire=_acquire_vm,
    release=_release_vm,
    acquire_idempotent=True,
)


class BuildDemo(Task[Path]):
    title = "Build the demo from this checkout"

    def run(self, inputs: TaskInputs) -> TaskOutcome[Path]:
        # Gradle, not a prebuilt artefact: the point is to report on the code in the working tree.
        subprocess.run(["./gradlew", "e2eDistribution", "-q"], cwd=REPO, check=True)
        archive = REPO / "build" / "e2e-dist.tgz"
        subprocess.run(
            ["tar", "czf", str(archive), "-C", str(REPO / "build"), "e2e-dist"], check=True
        )
        workflow_log(f"{archive.name}, {archive.stat().st_size // 1024} KiB")
        return TaskOutcome(value=archive)


class InstallRuntime(Task[str]):
    title = "Install containerd and crun, rootless"

    def run(self, inputs: TaskInputs) -> TaskOutcome[str]:
        _ssh(PROVISION)
        versions = _ssh(
            "crun --version | head -1; /usr/local/bin/containerd --version;"
            " ls /run/user/1000/containerd/containerd.sock"
        )
        for line in versions.strip().splitlines():
            workflow_log(line)
        return TaskOutcome(value=versions)


class DefineNetwork(Task[str]):
    title = "Define the CNI network the demo attaches to"

    def run(self, inputs: TaskInputs) -> TaskOutcome[str]:
        conflist = json.dumps(NETWORK_CONFLIST, indent=2)
        _ssh(
            f"mkdir -p {REMOTE_HOME}/.config/cni/net.d && "
            f"cat > {REMOTE_HOME}/.config/cni/net.d/10-{NETWORK}.conflist <<'CONF'\n{conflist}\nCONF"
        )
        workflow_log(f"{NETWORK}: 10.90.0.0/24, DNS 10.90.0.1 and 1.1.1.1, domain cjava.local")
        return TaskOutcome(value=NETWORK)


class DeployDemo(Task[str]):
    title = "Deploy the demo into the VM"

    def __init__(self, archive_of: BuildDemo) -> None:
        self._archive_of = archive_of

    def run(self, inputs: TaskInputs) -> TaskOutcome[str]:
        archive = REPO / "build" / "e2e-dist.tgz"
        _provider().transfer_to(_request(), source=archive, destination="e2e-dist.tgz")
        _ssh("rm -rf e2e-dist && tar xzf e2e-dist.tgz")
        jars = _ssh("ls e2e-dist/lib | wc -l").strip()
        workflow_log(f"{jars} jars deployed")
        return TaskOutcome(value=jars)


class RunScenarios(Task[str]):
    title = "Run the scenarios against rootless containerd"

    # The JVM has to live inside RootlessKit's network namespace: rootless containerd puts its
    # containers there, so a CNI attachment made from outside would wire the veth into a namespace
    # nothing in the container can see. user.home is set explicitly because inside that user
    # namespace the process is uid 0, and Java reads user.home from the passwd entry rather than
    # from HOME -- it would resolve to /root, which this user cannot write.
    SCRIPT = f"""
set -eu
XDG=/run/user/$(id -u)
CHILD=$(cat "$XDG/containerd-rootless/child_pid")
exec nsenter -t "$CHILD" -U --preserve-credentials -n -m \
  env HOME={REMOTE_HOME} \
      CONTAINERD_SOCKET="$XDG/containerd/containerd.sock" \
      E2E_NETWORK={NETWORK} \
      E2E_CNI_CONFIG={REMOTE_HOME}/.config/cni/net.d \
      E2E_CNI_PLUGINS=/usr/local/libexec/cni \
  java --enable-native-access=ALL-UNNAMED -Duser.home={REMOTE_HOME} \
       -cp "{REMOTE_HOME}/e2e-dist/classes:{REMOTE_HOME}/e2e-dist/lib/*" \
       io.nanofaas.containerd.e2e.E2eRun
"""

    def run(self, inputs: TaskInputs) -> TaskOutcome[str]:
        output = _ssh(self.SCRIPT)
        for line in output.splitlines():
            if line.strip():
                workflow_log(line.rstrip())
        # The demo reports its own tally; trusting the exit code alone would let a run that
        # printed nothing pass as a run in which everything held.
        tally = re.search(r"(\d+) scenarios, (\d+) failed", output)
        if tally is None:
            raise RuntimeError("the demo did not report a tally; see its output above")
        total, failed = int(tally.group(1)), int(tally.group(2))
        if failed:
            raise RuntimeError(f"{failed} of {total} scenarios failed")
        return TaskOutcome(value=f"{total} scenarios, none failed")


def main() -> int:
    build = BuildDemo()
    workflow = Workflow(workflow_id="containerd-java-e2e", keep=bool(os.environ.get("KEEP_VM")))
    workflow.add(build)
    workflow.add(InstallRuntime(), requires=(vm,))
    workflow.add(DefineNetwork(), requires=(vm,))
    workflow.add(DeployDemo(build), requires=(vm,))
    workflow.add(RunScenarios(), requires=(vm,))

    # A failed task is re-raised by the engine rather than reported in the result, and the VM is
    # released on the way out either way -- so the teardown still runs when a scenario fails,
    # which is the case that would otherwise leave a machine behind.
    with bind_workflow_sink(ConsoleSink()):
        try:
            result = workflow.run()
        except Exception as failure:
            print(f"\nworkflow failed: {failure}", file=sys.stderr)
            return 1
    print(f"\nworkflow passed: {len(result.tasks)} units")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
