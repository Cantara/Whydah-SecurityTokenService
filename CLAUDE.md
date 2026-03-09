# Whydah-SecurityTokenService

## Purpose
The core token generation and security session management service for the Whydah IAM system. Issues UserTokens and ApplicationTokens, manages security sessions, and serves as the central authentication authority that all other Whydah components depend on.

## Tech Stack
- Language: Java 21
- Framework: Jersey 3.x, Grizzly 4.x, Google Guice 5.x
- Build: Maven
- Key dependencies: Whydah-Admin-SDK, Valuereporter-Agent, Jersey, Grizzly

## Architecture
Standalone microservice that acts as the central security token authority. Authenticates applications via application credentials, authenticates users via user credentials or tickets, and issues XML-based security tokens containing user roles and application access. Supports DEV mode with file-based token templates for testing. All other Whydah services validate tokens against STS.

## Key Entry Points
- `/tokenservice/logon` - Application logon endpoint
- `/tokenservice/user/{appTokenId}/{userTicket}/usertoken` - User token endpoint
- `/tokenservice/health` - Health check (port 9998)
- DEV mode: `t_<username>.token` files for test tokens

## Development
```bash
# Build
mvn clean install

# Run
java -jar target/SecurityTokenService-*.jar

# Verify
curl http://localhost:9998/tokenservice/health
```

## Domain Context
Central authentication authority for the Whydah IAM ecosystem. All authentication and authorization decisions flow through STS. Issues security tokens that encode user identity, roles, and application access permissions.
