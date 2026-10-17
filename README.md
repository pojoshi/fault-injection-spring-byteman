# Fault injection with Byteman

A small Spring + Hibernate/JPA service, and a JUnit suite that breaks it on purpose. [Byteman](https://byteman.jboss.org/) rewrites bytecode at runtime, so the tests get a real dropped socket and a real timed-out statement from the JDBC layer, not a mock pretending to fail. Then we assert on whatever exception actually comes out.

Why not just mock the DataSource? Because the interesting part of a database outage isn't whether your code has a `catch`. It's which exception your stack surfaces after Hibernate, JPA, and Spring have each wrapped it. That's easy to assume and cheap to check, once you can cause the failure.

This began as an experiment in December 2022. I've since cleaned it up, fixed a few real bugs, and re-checked claims against real output.

## What's tested

**Database down.** A one-shot rule lets the first connection to port 3306 through, so `create()` succeeds, then refuses the second, so `find()` fails. The chain I actually saw:

```
IOException "Connection refused (simulated)"   <- what Byteman throws
 -> CJCommunicationsException -> CommunicationsException   (MySQL driver)
 -> JDBCConnectionException                                 (Hibernate)
 -> PersistenceException                                    (JPA)
 -> CannotCreateTransactionException                         <- what the test asserts
```

**Database timeout.** Byteman throws a `MySQLTimeoutException` the moment the driver executes the query:

```
MySQLTimeoutException "Statement timed out (simulated)"   <- what Byteman throws
 -> QueryTimeoutException                                   (Hibernate)
 -> HibernateJdbcException                                   <- what the test asserts
```

**Thread failure.** The second of two threads gets an injected `NullPointerException`. It dies on its own thread, so the test checks that only one thread got far enough to increment the shared counter.

**Thread synchronization.** No fault here. Byteman's `joinEnlist`/`joinWait` block the test until all 20 workers finish, so the assertion doesn't race them.

There's also a no-fault control test and three small sanity tests that show Byteman can rewrite a return value. If those fail, the setup is broken, not the application.

## Running it

You need Docker and nothing else. The build runs in a Maven + JDK 8 container.

```
docker compose up -d      # wait until `docker compose ps` says healthy

docker run --rm --network fault-injection-spring-byteman_default \
  -v "$PWD":/app -w /app \
  maven:3.8.6-eclipse-temurin-8 \
  mvn -Ddb.url="jdbc:mysql://mysql:3306/customers?enableQueryTimeouts=true&useSSL=false&allowPublicKeyRetrieval=true" test
```

You should see `Tests run: 9, Failures: 0, Errors: 0` and `BUILD SUCCESS`. I got that three times in a row, to rule out order-dependent flakiness.

A few things worth knowing:

- **Expect alarming stack traces in a green build.** The faults are injected on purpose, and Hibernate and Byteman log them.
- **It needs JDK 8.** The DB-down rule targets `java.net.AbstractPlainSocketImpl`, the older blocking socket class. I haven't tried newer JDKs.
- **It's not Spring Boot.** It's Spring 3.2.2 and Hibernate 4.2, wired by hand. I left the 2013-era versions alone, because the rules depend on version-specific internals.
- Tested with JDK 1.8.0_352, MySQL 8.0.46, Docker on arm64.

The DB URL, username, and password can be overridden with `-Ddb.url=`, `-Ddb.username=`, and `-Ddb.password=`.

## License

MIT. See `LICENSE`.
