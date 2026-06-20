# BKS Auth Server

Kotlin/Ktor backend for the first server-side auth migration.

## Local run

```bash
./gradlew :server:run
```

Default port: `8080`.

## Environment

- `PORT` - HTTP port, defaults to `8080`.
- `BKS_DB_PATH` - SQLite database path, defaults to `build/bks-app.db`.
- `BKS_FILES_DIR` - uploaded project files directory, defaults to `build/project-files`.
- `BKS_JWT_SECRET` - token signing secret.
- `BKS_DEV_LOGIN_ENABLED` - enables test-only admin login when set to `true`.

## API

- `GET /health`
- `POST /auth/register`
- `POST /auth/login`
- `POST /auth/dev-login` test-only admin login, requires `BKS_DEV_LOGIN_ENABLED=true`
- `POST /auth/recover`
- `GET /auth/me` with `Authorization: Bearer <token>`
- `PUT /auth/me` with `Authorization: Bearer <token>`
- `GET /auth/users/{uid}` with `Authorization: Bearer <token>`
- `GET /users` with `Authorization: Bearer <token>`
- `GET /users/{uid}` with `Authorization: Bearer <token>`
- `PUT /admin/users/{uid}/access` with administrator token
- `GET /admin/users/{uid}/access-history` with administrator token
- `GET /projects` with `Authorization: Bearer <token>`
- `POST /projects` with admin token
- `DELETE /projects/{id}` with admin token
- `GET /projects/{id}/file`
- `GET /schedules?objectId={id}` with `Authorization: Bearer <token>`
- `POST /schedules` with administrator token
- `PUT /schedules/{id}` with administrator token
- `DELETE /schedules/{id}` with administrator token
- `PUT /schedules/{id}/progress` with foreman token

## Production paths

Current server layout:

- App: `/opt/bks-app/server`
- SQLite database: `/opt/bks-app/data/bks-app.db`
- Environment file: `/etc/bks-app.env`
- systemd service: `bks-auth`
- Public API base URL: `http://45.137.153.112/api`
- nginx API upload limit: `client_max_body_size 100M;`
