# Q5b - OPTIONAL: Dockerfile (only if your host requires Docker)  (OPTIONAL - run only if the user asks)

Size: S. Why: Some hosts (for example Render) run Java only through a Docker image. Skip if your host builds Maven projects directly.

Follow the START and FINISH protocol in AGENTS.md (set your row in docs/PROGRESS.md to
IN_PROGRESS first; at the end set it to DONE, commit with the message below, do not push).

TASK Q5b - add backend/Dockerfile (multi-stage: Maven build, then a JRE image matching the pom's Java version), non-root user, JAVA_OPTS env, SPRING_PROFILES_ACTIVE=production, PORT/8080, healthcheck on /actuator/health/readiness, plus backend/.dockerignore (exclude target, .env*, .idea, .git). Never copy any .env file into the image. Do not run containers. Add one short "Docker hosts" section to DEPLOYMENT.md (create the file only if it does not exist).

Commit message: "Add Dockerfile"
