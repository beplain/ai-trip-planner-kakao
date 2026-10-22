# Talk Calendar

New trips are saved to the DB first. A separate registration then adds one all-day event to the saving user's primary Kakao calendar. Dates are inclusive in the app; the API event ends at midnight on the day after the trip ends.

## Setup

1. In Kakao Developers, enable the optional consent item `talk_calendar` (톡캘린더 및 일정 생성, 조회, 편집/삭제).
2. Confirm the app has Talk Calendar API permission. Without approval, testing can be restricted to app team members.
3. Restart the backend and reload the app. The existing `ddl-auto=update` creates `trip_calendar_events` and adds token columns to `users`.
4. Sign in with Kakao (not development login), generate a trip, then use `톡캘린더 연결` in the draft. Grant consent using the same Kakao account.
5. Save the trip. Its details screen shows registration status and a link to Talk Calendar. Previously saved trips can be registered from this screen too.

The app requests `talk_calendar` only when the connection button is used. A missing calendar permission does not prevent ordinary login or saving a trip.

## Token storage

Access and refresh tokens are encrypted with AES-256-GCM. A new random key is created at `data/kakao-token.key` on first startup. Keep this file private and preserve it across restarts; it is excluded by the existing `data/` gitignore rule. Always start from the same backend working directory. Losing the key requires users to reconnect Kakao; it does not delete trips.

For deployment, `APP_KAKAO_TOKEN_ENCRYPTION_KEY` can supply a Base64-encoded 32-byte encryption key instead of the local file. All instances must use the same key. Do not rotate or change it without reconnecting users or migrating encrypted token bundles.

## Behavior and limitations

- `GET /api/trips/{id}/calendar` checks the caller's status; `POST` registers in the caller's calendar. Both require membership and the app JWT.
- A durable `(trip_id, user_id)` claim is committed before the remote request. CREATED records do not create duplicate events.
- Definite API rejections can be retried. A timeout, uncertain response, or crash after claiming is not blindly resent; check Talk Calendar first. PENDING/UNKNOWN may need operator reconciliation with the remote calendar before the DB state is corrected.
- Calendar failure does not roll back the trip. Token refresh uses the stored refresh token and retains it when Kakao omits a replacement.
- This feature registers the initial saved snapshot. Editing/deleting a trip does not edit/delete its remote calendar event. The app explains this on the details screen.
- Claims use scalar user/trip IDs. Old registration audit rows are retained if trips/users are removed; they contain no OAuth tokens. Apply the service's data-retention policy when deploying.
- Real Kakao consent and event creation require the configured account and permissions; automated tests use fake HTTP responses and an isolated in-memory H2 database.

Official reference: https://developers.kakao.com/docs/ko/talkcalendar/rest-api
