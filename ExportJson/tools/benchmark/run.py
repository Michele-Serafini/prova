#!/usr/bin/env python3
"""Compare a Git reference and working tree using the same suspended real JVM.

Dependencies stay on the parent classpath; the two plugin implementations are
compiled and loaded in isolated classloaders. Nothing is changed in the repo.
"""

import argparse
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--classpath", required=True,
                        help="Dependency JARs only, separated with the platform path separator; Java wildcards are accepted")
    parser.add_argument("--java-home", type=Path,
                        help="JDK 21 installation; defaults to JAVA_HOME or java/javac from PATH")
    parser.add_argument("--baseline-ref", default="5b80db2",
                        help="Git baseline reference (default: pre-optimization version)")
    parser.add_argument("--spring", action="store_true",
                        help="Also benchmark Spring lookup; requires Spring runtime JARs in --classpath")
    args = parser.parse_args()

    here = Path(__file__).resolve().parent
    repo = Path(subprocess.check_output(["git", "-C", str(here), "rev-parse", "--show-toplevel"], text=True).strip())
    baseline = subprocess.check_output(["git", "-C", str(repo), "rev-parse", "--verify", args.baseline_ref + "^{commit}"], text=True).strip()
    head = subprocess.check_output(["git", "-C", str(repo), "rev-parse", "HEAD"], text=True).strip()
    java_home = args.java_home or (Path(os.environ["JAVA_HOME"]) if os.environ.get("JAVA_HOME") else None)
    suffix = ".exe" if os.name == "nt" else ""
    java = str(java_home / "bin" / ("java" + suffix)) if java_home else shutil.which("java")
    javac = str(java_home / "bin" / ("javac" + suffix)) if java_home else shutil.which("javac")
    if not java or not javac:
        parser.error("A JDK with java and javac is required; set --java-home")

    subprocess.run([java, "-version"], check=True)
    print("Baseline commit:", baseline, flush=True)
    print("Current source:", head, "(working tree)", flush=True)
    print("Timing: 2 warmups, median of 5 scans; startup/JSON rendering/tracing excluded.", flush=True)
    print("JDWP command counts: separate warmed scan; outputs must match exactly.", flush=True)

    names = ["RuntimeScannerService", "SpringProxyReader"]
    if args.spring:
        names += ["ExportTarget", "SpringBeanLocator", "RuntimeValueResolver"]
    with tempfile.TemporaryDirectory(prefix="obj2json-benchmark-") as temporary:
        work = Path(temporary)
        baseline_sources = []
        current_sources = []
        for name in names:
            relative = "ExportJson/src/main/java/pluginjson/scanner/" + name + ".java"
            destination = work / "source" / "pluginjson" / "scanner" / (name + ".java")
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_bytes(subprocess.check_output(["git", "-C", str(repo), "show", baseline + ":" + relative]))
            baseline_sources.append(destination)
            current_sources.append(repo / relative)
        # Limits are deliberately pure Java and do not require booting IntelliJ.
        current_sources.append(repo / "ExportJson/src/main/java/pluginjson/settings/ScanLimits.java")
        if args.spring:
            current_sources.append(repo / "ExportJson/src/main/java/pluginjson/settings/SpringLookupLimits.java")
        source_digest = hashlib.sha256()
        for source in current_sources:
            source_digest.update(source.relative_to(repo).as_posix().encode("utf-8") + b"\0")
            source_digest.update(source.read_bytes())
        print("Current measured source SHA-256:", source_digest.hexdigest(), flush=True)
        old_classes, new_classes, harness_classes = (work / part for part in ("baseline", "current", "harness"))
        for directory in (old_classes, new_classes, harness_classes):
            directory.mkdir()
        for sources, classes in ((baseline_sources, old_classes), (current_sources, new_classes)):
            subprocess.run([javac, "-g", "-cp", args.classpath, "-d", str(classes), *map(str, sources)], check=True)
        harnesses = [here / "BenchmarkFixture.java", here / "ScannerBenchmark.java"]
        if args.spring:
            harnesses += [here / "SpringBenchmarkFixture.java", here / "SpringBenchmark.java"]
        subprocess.run([javac, "-g", "-cp", args.classpath, "-d", str(harness_classes), *map(str, harnesses)], check=True)
        classpath = str(harness_classes) + os.pathsep + args.classpath
        subprocess.run([java, "-cp", classpath, "benchmark.ScannerBenchmark", str(old_classes), str(new_classes)], check=True)
        if args.spring:
            subprocess.run([java, "-cp", classpath, "benchmark.SpringBenchmark", str(old_classes), str(new_classes)], check=True)


if __name__ == "__main__":
    main()
