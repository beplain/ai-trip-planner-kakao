# Maven backend

Requires JDK 17 or later. Maven is downloaded automatically by the wrapper.

## Windows

```powershell
cd C:\projects\ai-trip-planner-kakao\backend
.\mvnw.cmd spring-boot:run
```

Use the same DB, Kakao, and OpenAI environment variables as before.
Stop any existing backend on port 8080 before starting another instance.

## Test and package

```powershell
.\mvnw.cmd clean verify
java -jar target/trip-planner-0.1.0.jar
```

On macOS/Linux use `sh mvnw` instead of `.\mvnw.cmd`.

## Eclipse

1. File > Import > Maven > Existing Maven Projects.
2. Select the backend directory containing pom.xml, then Finish.
3. If already imported as Gradle, remove the old project from the workspace
   without deleting its contents, then import using Maven.
4. Run TripPlannerApplication.java as a Java Application.
5. Set environment variables in Run Configurations > Environment, or restart
   Eclipse after saving Windows user environment variables.
6. Set the run configuration working directory to this backend directory.

The backend uses Maven. The obsolete Gradle cache, build output and wrapper directories have been removed.
