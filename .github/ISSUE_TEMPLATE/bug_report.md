---
name: Bug report
about: A wrong verdict, a crash, or a report that will not open
title: ''
labels: bug
assignees: ''
---

**What happened**
A clear description of the wrong or missing behavior.

**What you expected**
The verdict or finding you expected instead.

**Reproduce**
```bash
# exact jvmd command, e.g.
jvmd investigate 12345 --samples 6
```

**Environment**
- `jvmd --version`:
- JDK running jvmd:
- JDK of the target JVM:
- OS:

**Evidence**
Attach `report.json` (redact hostnames, user names and package names you cannot share).
If the parser missed something, attach the relevant part of `thread-dump.txt`.
