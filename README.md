# Hybrid Haven API

Hybrid Haven is a hybrid-work coordination tool for an office. Employees sign in with GitHub, book a desk in a named office zone for a given day, mark themselves on vacation, and join company events. This repository holds the Spring Boot REST service that backs the web client.

The service was built as a graduate programme project at BBD and was deployed to AWS behind a real domain.

## The four repositories

| Repository | Role |
| --- | --- |
| [Hybrid-Haven-API](https://github.com/Umang-Vadadoriya/Hybrid-Haven-API) | This repo. Spring Boot REST service and the EC2 infrastructure it runs on. |
| [Hybrid-Haven-DB](https://github.com/Umang-Vadadoriya/Hybrid-Haven-DB) | SQL Server schema, Liquibase migrations, stored procedures, and the RDS infrastructure. |
| [Hybrid-Haven-Web](https://github.com/Umang-Vadadoriya/Hybrid-Haven-Web) | Vanilla JavaScript frontend that calls this API. |
| [Hybrid-Haven-DOCS](https://github.com/Umang-Vadadoriya/Hybrid-Haven-DOCS) | UML design artifacts. Use case, domain model, class, robustness, and sequence diagrams. |

The API and the web client run on the same EC2 instance behind one nginx host. The API owns no schema of its own, so the DB repo has to be migrated before this service will start. See "Schema ownership" below.

## Tech stack

Verified from `pom.xml` and the source.

- Java 21
- Spring Boot 3.2.3 with `spring-boot-starter-web`, `spring-boot-starter-data-jpa`, and `spring-boot-starter-actuator`
- Hibernate ORM 6.4.1
- Microsoft SQL Server via the `mssql-jdbc` driver
- Lombok for entity boilerplate
- Maven, with the wrapper checked in (`mvnw`)
- JUnit 5 and Mockito through `spring-boot-starter-test`
- Terraform 1.2.5 for the EC2 instance
- GitHub Actions for build, test, deploy, and Terraform apply

## Domain modules

The code is laid out as controller, service, repository, and entity packages under `src/main/java/com/hybrid/hybridhavenapi`. Each domain area has one class per layer.

| Domain | Routes | What it does |
| --- | --- | --- |
| Employee | `/employees` | List, fetch by id, search by name, look up who an employee reports to, create, update, delete. |
| EmployeeContact | `/employeeContact/` | Email and phone number per employee, including a lookup by email address that the web client uses to match a GitHub account to an employee record. |
| DeskBooking | `/desk-bookings` | Create a booking, list all, fetch by id, list by date, delete. |
| Vacation | `/vacations` | Create, list, fetch by id, list by date, update, delete. |
| Events | `/events` | List, fetch by id, fetch by name, create, update, delete. |
| EventsEmployees | `/events-employee` | Join table between an event and the employees attending it. |
| NeighbourHood | `/neighbourhoods` | Office zones (for example Hot Desk, Collab) and the number of desks each one has. |
| Home | `/` and `/auth/code` | Version string and the GitHub OAuth code exchange. |

Two pieces of booking logic live in the controllers rather than in plain CRUD:

- `DeskBookingController` rejects a booking with `409 Conflict` if the employee already has a desk booked on that date, and also if the employee already has a vacation covering that date. It checks both `DeskBookingService` and `VacationService` before saving.
- `VacationController` rejects a vacation with `409 Conflict` if an existing vacation for that employee overlaps the requested range. The overlap query is a JPQL `@Query` on `VacationRepository`.

Date path variables on the by-date endpoints use the `dd.MM.yyyy` format, set with `@DateTimeFormat`. This matters when calling the API by hand, because it is not the ISO format the JSON bodies use.

## Authentication

There is no Spring Security dependency. Authentication is a single servlet filter, `Config/GithubTokenAuthentication`, which extends `OncePerRequestFilter` and runs on every request.

1. `OPTIONS` requests are answered directly with permissive CORS headers and never reach a controller.
2. `/` and `/auth/code` are public. Everything else requires a token.
3. The filter reads the `Authorization: Bearer <token>` header and validates the token by calling `https://api.github.com/user` with it. Any non-empty response means the token is good. Anything else returns `401`.

The same class also holds `generateToken`, which the `/auth/code` endpoint calls to exchange a GitHub OAuth authorization code for an access token using the `CLIENT_ID` and `CLIENT_SECRET` environment variables.

Two consequences worth knowing. Every authenticated request costs one outbound call to GitHub, because no token or identity is cached. And the filter only proves that a token is a valid GitHub token, so it authenticates the caller as a GitHub user but does not tie them to an employee record or check any permission.

CORS is configured in two places. The filter handles preflight with a wildcard origin, and `Config/webconfig` registers a `CorsRegistry` mapping with an explicit allowed origin list covering local development, the EC2 public address, and the deployed domain.

## Exception handling

`Config/GlobalExceptionHandler` is a `@ControllerAdvice` with one handler bound to `Exception`. Anything that escapes a controller comes back as `500` with a JSON body shaped by the `ResponseMessage` record, which is a single `message` field. Expected outcomes such as "already booked" or "not found" are returned explicitly by the controllers rather than raised as exceptions, so they keep their own status codes.

## Tests

`src/test/java` holds three classes:

- `EmployeeControllerTests` and `EventControllerTests` are `@WebMvcTest` slices. Each mocks its service with `@MockBean` and drives the controller through `MockMvc`, covering the happy paths for listing and lookup plus the error path where the employee service throws.
- `HybridHavenApiApplicationTests` is a context load check.

The `@Test` annotations in all three classes are commented out, and so is the `@SpringBootTest` annotation on the context test. The test bodies are complete and the workflow runs `mvn test`, but as the code stands nothing is asserted. Uncommenting the annotations is what turns them back on. The context test additionally needs the database environment variables to be present, which is why it was disabled.

## Infrastructure

`Terraform/hybrid-haven.tf` provisions the host the API runs on, in `eu-west-1`:

- A `t2.micro` EC2 instance from an Amazon Linux AMI, tagged `HybridHavenAPI`
- A key pair built from the checked-in public key `deployer-key-ec2.pem.pub`, with its fingerprint recorded in `Terraform/FingerPrint`
- A security group, currently open to `0.0.0.0/0` on all ports
- `user_data` that installs Amazon Corretto Java 21 at first boot
- Outputs for the instance id and key name

State is kept in S3. `Terraform/provider.tf` declares an empty `backend "s3" {}` block, and the bucket, key, and region are supplied by `terraform init -backend-config` in CI, so no bucket name is committed.

## GitHub Actions

`.github/workflows/Automation.yml` triggers on pushes to `main` and on pull requests that touch `src/**`.

- `Build-And-Test` sets up JDK 21, runs `mvn clean package`, uploads the versioned JAR as an artifact, then runs `mvn test`.
- `Deploy-And-Run` runs only on a push to `main`. It downloads the JAR, SSHes to the EC2 host to kill whatever is listening on port 8080 and delete the previous JAR, copies the new JAR over SCP, then relaunches it under `nohup` with the database and OAuth secrets exported into the environment.

The version number is pinned as a workflow-level `Version` variable and has to be kept in step with the version in `pom.xml`, because the artifact and JAR filenames are built from it.

`.github/workflows/Terraform.yml` triggers on changes under `Terraform/`. It assumes an AWS role over OIDC rather than using static keys, runs `fmt`, `init`, and `validate`, runs `plan` on pull requests and posts the plan back as a PR comment, and runs `apply -auto-approve` on a push to `main`.

## Running it locally

You need Java 21, Maven (or use the wrapper), and a reachable SQL Server instance whose schema has already been migrated by the [DB repo](https://github.com/Umang-Vadadoriya/Hybrid-Haven-DB).

`src/main/resources/application.properties` reads five values from the environment. Nothing is hardcoded and there are no defaults, so all of the database ones must be set or startup fails.

| Variable | Used for |
| --- | --- |
| `DB_URL` | JDBC URL of the SQL Server database |
| `DB_USERNAME` | Database user |
| `DB_PASSWORD` | Database password |
| `CLIENT_ID` | GitHub OAuth app client id, read at request time by the token exchange |
| `CLIENT_SECRET` | GitHub OAuth app client secret |

The `DB_URL` value is a standard SQL Server JDBC URL of the form `jdbc:sqlserver://<host>:1433` followed by the usual `databaseName` property.

```
export DB_URL=<jdbc url>
export DB_USERNAME=<user>
export DB_PASSWORD=<password>
export CLIENT_ID=<github oauth client id>
export CLIENT_SECRET=<github oauth client secret>

./mvnw clean package
./mvnw spring-boot:run
```

The service listens on port 8080. `GET /` returns the version string and needs no token. Every other endpoint needs a GitHub access token in an `Authorization: Bearer` header. Actuator endpoints are all exposed (`management.endpoints.web.exposure.include=*`) and sit behind the same filter.

## Schema ownership

`spring.jpa.hibernate.ddl-auto=validate`, so Hibernate never creates or alters a table. It only checks that the entities match what is already there, and refuses to start if they do not. The schema is owned entirely by the [DB repo](https://github.com/Umang-Vadadoriya/Hybrid-Haven-DB) and applied by Liquibase. That split is deliberate, and it is why the two repositories have to be deployed in order.

The entities use `PhysicalNamingStrategyStandardImpl` with explicit `@Column` names, so the Java camel case names map to the SQL Server PascalCase columns without Hibernate rewriting them.

## Known rough edges

Left as they are rather than cleaned up after the fact, since this is the state the project shipped in.

- The test annotations are commented out, as described above.
- `pom.xml` carries a stray `junit:junit:3.8.1` dependency alongside `spring-boot-starter-test`. It is unused.
- In `Automation.yml` the deploy job declares `needs: build-and-test` while the build job is defined as `Build-And-Test`.
- The Terraform security group allows all inbound traffic from anywhere.
- `HomeController` constructs `GithubTokenAuthentication` with `new` instead of injecting it, so the OAuth exchange runs outside the Spring context.

## Contributions

Built by two developers over roughly four months in 2024, tracked in Jira with one branch per ticket and merged through pull requests.

**Krunal Rana** ([@krunal-BBD](https://github.com/krunal-BBD)) wrote most of the application layer. By current line attribution he owns about 294 of 431 lines across the controllers, 227 of 317 across the services, and 173 of 192 in the tests.

**Umang Vadadoriya** ([@Umang-Vadadoriya](https://github.com/Umang-Vadadoriya)) wrote the JPA entities (190 of 197 lines), the whole `Config` package including the GitHub token filter, the CORS configuration and the exception handler (141 lines, all of it), and all of the infrastructure and CI, meaning both Terraform files and both workflows.

Across `src` the current lines split roughly 764 to Krunal and 634 to Umang. Raw commit counts (86 non-merge commits to Umang against 8 to Krunal) overstate that gap considerably, because they also count the merges, infrastructure changes, and version bumps, so the line attribution above is the more honest measure.

The [DOCS repo](https://github.com/Umang-Vadadoriya/Hybrid-Haven-DOCS) has the design diagrams that this layout follows, including sequence diagrams for the desk booking, employee, and event flows.
