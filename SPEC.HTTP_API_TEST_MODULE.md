# Kode REST API Collection / Test Client — Implementation Specification

Implement a REST API client/test component in **Kode** that provides a terminal UI for creating, editing, organizing, persisting, importing/exporting, and executing HTTP requests.

The persistent data model must be based on **Postman Collection Format v2.1.0 JSON**.

This is deliberately **not** a clone of the Postman application. It is a Kode-native TUI editor and request runner whose persistent collection representation is compatible with Postman Collection v2.1.

The workspace contains exactly **one REST collection**, stored inside the workspace `.kode.json` configuration.

## 1. Primary goals

Implement the following workflow:

1. A workspace owns one REST API collection.
2. The collection is displayed as a tree in the left-side panel.
3. The collection may contain requests directly at its root.
4. The collection may contain arbitrarily nested folders.
5. Folders may contain requests and other folders.
6. Collection, folders, and requests may define variables.
7. Collection and folders may define authentication inherited by descendants.
8. Requests may inherit authentication or override it.
9. Selecting any collection/folder/request node opens its editor in the main content area.
10. A request editor allows the user to configure and execute the HTTP request.
11. The response is displayed in a bottom panel of the request editor.
12. Folder and collection nodes can run all descendant requests.
13. The entire collection can be imported/exported as a standard Postman v2.1 `.postman_collection.json`.

The supplied screenshots should be treated as UX references for the hierarchy and editing workflow, not as pixel-perfect UI requirements.

---

# 2. Postman format compatibility

Target this schema:

```text
Postman Collection Format v2.1.0
```

A generated collection must contain:

```json
{
  "info": {
    "name": "My API",
    "schema": "https://schema.getpostman.com/json/collection/v2.1.0/collection.json"
  },
  "item": []
}
```

The implementation must preserve standard Postman fields even when Kode does not currently expose them in its UI.

This is important for import/export compatibility.

Do **not** deserialize an imported collection into a narrow model and then accidentally discard unknown/unimplemented fields on save.

Either:

* model the complete relevant v2.1 structure, or
* retain the original JSON AST and provide typed accessors/views over it.

Lossless structural round-tripping is preferred.

Kode-specific state must **not** be inserted into the Postman collection object. Put Kode-specific metadata beside it in `.kode.json`.

For example:

```json
{
  "restApi": {
    "version": 1,

    "collection": {
      "info": {
        "_postman_id": "uuid",
        "name": "Mistral AI API",
        "schema": "https://schema.getpostman.com/json/collection/v2.1.0/collection.json"
      },

      "variable": [
        {
          "key": "baseUrl",
          "value": "https://api.mistral.ai",
          "type": "string"
        },
        {
          "key": "bearerToken",
          "value": "",
          "type": "string"
        }
      ],

      "auth": {
        "type": "bearer",
        "bearer": [
          {
            "key": "token",
            "value": "{{bearerToken}}",
            "type": "string"
          }
        ]
      },

      "item": []
    },

    "ui": {
      "selectedPath": null,
      "responsePanelHeight": 12
    }
  }
}
```

`restApi.ui` is Kode state and must never be exported as part of the Postman collection.

---

# 3. Collection tree

Add a REST/API tree to the left-side panel.

The root is the collection itself:

```text
▼ Mistral AI API
    ▼ v1
        ▼ models
            GET   List Models
        ▶ files
        ▼ fine_tuning
            ▼ jobs
                GET   Get Fine Tuning Jobs
                POST  Create Fine Tuning Job
    ▶ batch/jobs
    ▶ chat
    POST  Fim Completion
    POST  Agents Completion
    POST  Embeddings
```

Request entries should display their HTTP method before their name.

The method should be visually distinguishable using the existing Kode theme facilities where practical:

```text
GET
POST
PUT
PATCH
DELETE
HEAD
OPTIONS
```

Do not restrict requests to these methods. Postman permits custom method strings, so the method field must ultimately accept arbitrary HTTP method names. The v2.1 schema also explicitly permits custom methods. ([Gist][2])

## Tree operations

Collection root:

```text
Add Request
Add Folder
Run Collection
Rename Collection
Import Collection
Export Collection
```

Folder:

```text
Add Request
Add Folder
Run Folder
Rename
Duplicate
Move Up
Move Down
Move To...
Delete
```

Request:

```text
Open
Send
Rename
Duplicate
Move Up
Move Down
Move To...
Delete
```

Use existing Kode conventions for context menus, keyboard shortcuts, focus handling, mouse handling, confirmation dialogs, etc.

Do not invent a separate interaction framework just for this component.

A visible `+` action on the collection and folders is desirable if compatible with the existing tree widget.

Deleting a non-empty folder must require confirmation.

Moving/reordering nodes modifies the order of the corresponding `item[]` array. Array order is significant and must be preserved.

---

# 4. Node types and Postman mapping

There are three editor-visible node types.

## Collection

Maps directly to:

```json
{
  "info": {},
  "variable": [],
  "auth": {},
  "item": []
}
```

## Folder

Maps to a Postman `item-group`:

```json
{
  "name": "models",
  "description": "...",
  "variable": [],
  "auth": {},
  "item": []
}
```

Folders may recursively contain folders.

## Request

Maps to a Postman Item:

```json
{
  "id": "...",
  "name": "List Models",
  "description": "...",

  "variable": [],

  "request": {
    "method": "GET",
    "url": {
      "raw": "{{baseUrl}}/v1/models"
    },
    "header": []
  },

  "response": []
}
```

Note carefully that request-local general variables belong to:

```text
item.variable
```

while request authentication belongs to:

```text
item.request.auth
```

Do not place request auth on `item.auth`.

---

# 5. Variable system

Implement variable editing at all three scopes:

```text
Collection
  ↓
Folder
  ↓
Nested Folder
  ↓
Request
```

All three are valid Postman v2.1 variable scopes.

The editor should use a table resembling:

```text
Enabled | Variable       | Value                         | Type
--------+----------------+-------------------------------+--------
[x]     | baseUrl        | https://api.mistral.ai       | string
[x]     | bearerToken    | abcdef...                     | string
[x]     | retries        | 3                             | number
```

Support the v2.1 variable types:

```text
string
boolean
number
any
```

Persist standard fields where supplied:

```json
{
  "key": "baseUrl",
  "value": "https://api.example.com",
  "type": "string",
  "description": "...",
  "disabled": false
}
```

Variable references use:

```text
{{variableName}}
```

Resolve variables in at least:

```text
URL
query parameter keys/values
headers
request body
form fields
authentication values
```

## Resolution precedence

For a request located at:

```text
Collection
  Folder A
    Folder B
      Request
```

resolve general variables from most local to least local:

```text
Request variables
Folder B variables
Folder A variables
Collection variables
```

A local variable with the same key overrides the ancestor definition.

Disabled variables do not participate in resolution.

The request Variables editor should display both:

* variables defined directly on this request;
* inherited/effective variables, read-only, including their source.

Example:

```text
Variable       Value                    Source
-------------  -----------------------  ----------------
baseUrl        https://test.example     Folder: Test
bearerToken    ******                   Collection
userId         1234                     Request
```

Nested references should be resolved recursively with cycle detection.

For example:

```text
host = api.example.com
baseUrl = https://{{host}}
```

must resolve correctly.

Do not infinite-loop on:

```text
a={{b}}
b={{a}}
```

Report cyclic or unresolved substitutions clearly.

Unresolved variables should be highlighted before sending, but they should not corrupt the stored collection.

---

# 6. URL/path variables

Do not confuse general item variables with URL path variables.

Postman additionally supports:

```json
"request": {
  "url": {
    "raw": "{{baseUrl}}/users/:userId",
    "variable": [
      {
        "key": "userId",
        "value": "123"
      }
    ]
  }
}
```

These belong in the request **Params** editor under a separate `Path Variables` section.

They correspond to `:variable` placeholders in a URL.

General `{{variable}}` resolution and URL `:pathVariable` resolution should remain separate concepts internally.

---

# 7. Collection/folder editor

Selecting the collection or a folder opens its editor in the content pane.

Provide these tabs:

```text
Overview
Authorization
Variables
```

An optional advanced/raw view may also be provided.

## Overview

Collection:

```text
Name
Description
Collection schema/version
```

Folder:

```text
Name
Description
```

## Authorization

Collection:

```text
Auth Type:
    No Auth
    API Key
    Bearer Token
    Basic Auth
    ...
```

A collection cannot inherit authentication because it is the root.

Folder:

```text
Auth Type:
    Inherit auth from parent
    No Auth
    API Key
    Bearer Token
    Basic Auth
    ...
```

When inheritance is selected, show:

```text
Inherited from: Mistral AI API
Effective type: Bearer Token
```

or, for nested folders:

```text
Inherited from: Internal APIs
Effective type: Basic Auth
```

## Variables

Display variables owned by this exact node, followed by inherited/effective variables where appropriate.

---

# 8. Authentication inheritance

Authentication follows the collection hierarchy.

Example:

```text
Collection: Bearer
    Folder A: inherit
        Request A: inherit       -> Bearer

    Folder B: Basic
        Request B: inherit       -> Basic
        Request C: No Auth       -> none
```

Represent inheritance by **omitting** the local auth property.

Represent explicit no-auth as:

```json
{
  "type": "noauth"
}
```

For a request, inherited auth therefore means:

```json
"request": {
  "method": "GET",
  "url": "... "
}
```

not:

```json
"auth": {
  "type": "noauth"
}
```

Postman explicitly supports collection/folder authentication inheritance and request overrides. ([Postman Docs][3])

---

# 9. Authentication types

The Postman v2.1 schema defines:

```text
apikey
awsv4
basic
bearer
digest
edgegrid
hawk
noauth
oauth1
oauth2
ntlm
```

([Gist][4])

The model and serializer must be able to preserve all of these.

For the first implementation, native request execution must at minimum fully support:

```text
Inherit
No Auth
Basic Auth
Bearer Token
API Key
```

Basic:

```text
Username
Password
```

Bearer:

```text
Token
```

API Key:

```text
Key
Value
Add to:
    Header
    Query Params
```

For the more complex Postman auth types, preserve imported configuration losslessly and provide a generic attribute editor if a dedicated editor is not yet available.

Never silently ignore an unsupported authentication mechanism during execution.

Instead show, for example:

```text
Cannot send request:
AWS Signature v4 execution is not implemented yet.
```

This is preferable to accidentally sending an unauthenticated request.

OAuth2 may initially support an already-acquired access token. Interactive authorization-code/browser flows are outside the initial implementation.

---

# 10. Credentials

Authentication values may contain variables:

```text
{{bearerToken}}
{{username}}
{{password}}
```

Resolve those only at execution time.

Credential-looking fields should be masked in the normal editor:

```text
••••••••••••
```

with an explicit reveal action.

Do not print resolved credentials to application logs.

Do not include them in exception messages.

Do not include generated authorization headers in debug logs.

There is an unavoidable limitation: if a user places a literal secret in the Postman collection, it is stored as plaintext inside `.kode.json`. Postman v2.1 JSON itself is not a secure secret vault. Do not pretend otherwise.

A later keychain/secret-provider facility can replace literal values with variables, but that is not necessary for this feature.

---

# 11. Request editor layout

Selecting a request opens a request editor in the central content pane.

Top row:

```text
[ GET ▼ ] [ {{baseUrl}}/v1/models                      ] [ Send ]
```

Below that, tabs:

```text
Params
Authorization
Headers
Body
Variables
Settings
```

The lower portion of the content area is a collapsible/resizable **Response** panel.

Conceptually:

```text
┌─────────────────────────────────────────────────────────────┐
│ GET │ {{baseUrl}}/v1/models                       │ Send    │
├─────────────────────────────────────────────────────────────┤
│ Params │ Authorization │ Headers │ Body │ Variables │ ...   │
│                                                             │
│                 request configuration                       │
│                                                             │
├─────────────────────── Response ─────────────────────────────┤
│ 200 OK        143 ms        2.4 KB                          │
│ Body │ Headers │ Cookies │ Raw                              │
│                                                             │
│ {                                                           │
│   "object": "list",                                         │
│   ...                                                       │
│ }                                                           │
└─────────────────────────────────────────────────────────────┘
```

Reuse Kode's normal text editors and table/list widgets where possible.

---

# 12. Params tab

Provide separate sections for:

```text
Query Parameters
Path Variables
```

Query parameter table:

```text
Enabled | Key       | Value          | Description
--------+-----------+----------------+------------
[x]     | limit     | 100            |
[x]     | page      | {{page}}       |
[ ]     | debug     | true           |
```

Persist query parameters as standard Postman URL query entries.

Support:

```json
{
  "key": "page",
  "value": "{{page}}",
  "disabled": false,
  "description": "Page number"
}
```

Duplicate query parameter names must be allowed.

Editing the raw URL and editing the query table must remain synchronized.

Do not discard ordering or duplicate entries.

---

# 13. Headers tab

Provide an editable table:

```text
Enabled | Key             | Value                   | Description
--------+-----------------+-------------------------+------------
[x]     | Accept          | application/json        |
[x]     | X-Client        | kode                    |
[ ]     | X-Debug         | true                    |
```

Preserve:

```text
case
ordering
duplicates
disabled entries
descriptions
```

Authentication-generated headers should be calculated at execution time and should not automatically be persisted into `request.header`.

For example, Bearer auth may produce:

```text
Authorization: Bearer <token>
```

without adding that header permanently to the request definition.

---

# 14. Body tab

Support all Postman v2.1 request body modes defined by the schema: `raw`, `urlencoded`, `formdata`, `file`, and `graphql`. ([Gist][2])

## none

No body.

## raw

Use the normal Kode text editor.

Support language hints:

```text
JSON
XML
HTML
JavaScript
Text
```

These are primarily editor/highlighting metadata.

## x-www-form-urlencoded

Editable key/value table.

Support disabled fields.

## multipart/form-data

Editable table:

```text
Enabled | Key       | Type   | Value
--------+-----------+--------+-------------------
[x]     | name      | Text   | John
[x]     | avatar    | File   | ./images/me.png
```

File paths should preferably be stored relative to the workspace when possible.

## file

Single file body.

## GraphQL

Provide:

```text
Query
Variables
```

where Variables is JSON text.

---

# 15. Authorization tab for requests

The request editor must provide:

```text
Inherit auth from parent
No Auth
API Key
Bearer Token
Basic Auth
...
```

If inherited, display the effective auth configuration and source but do not make inherited credentials editable here.

Example:

```text
Type: Inherit auth from parent

Effective authorization:
Bearer Token
Inherited from collection "Mistral AI API"
Token: {{bearerToken}}
```

Selecting another type creates `request.auth`.

Selecting `Inherit` removes `request.auth`.

Selecting `No Auth` writes:

```json
"auth": {
  "type": "noauth"
}
```

---

# 16. Variables tab for requests

The request itself may define:

```json
{
  "name": "Get User",
  "variable": [
    {
      "key": "userId",
      "value": "100"
    }
  ],
  "request": { ... }
}
```

The editor must therefore support actual request-scoped variables rather than inventing a Kode-only representation.

Show local definitions separately from inherited variables.

---

# 17. URL representation

Postman permits a request URL to be either a string or a structured URL object.

Kode must accept and preserve both when importing.

For requests created by Kode, prefer the structured representation:

```json
"url": {
  "raw": "{{baseUrl}}/v1/models?limit={{limit}}",
  "query": [
    {
      "key": "limit",
      "value": "{{limit}}"
    }
  ]
}
```

The UI should primarily expose one editable raw URL field plus structured Params tables.

Do not require that every intermediate URL be syntactically valid while the user is editing it.

Validation should occur when necessary, particularly before execution.

---

# 18. Sending requests

The HTTP execution layer must be separate from the UI and persistence layers.

Conceptually:

```text
Postman collection model
        │
        ▼
Variable/auth resolver
        │
        ▼
Resolved request
        │
        ▼
HTTP executor
        │
        ▼
Execution result
        │
        ▼
Response UI
```

Do not let TUI widgets directly construct and send HTTP traffic.

Create an immutable execution snapshot when Send is invoked.

This prevents edits occurring during a request from mutating the in-flight request.

Execution must happen asynchronously/off the TUI thread.

The UI must remain responsive while a request is running.

`Send` should temporarily become something equivalent to:

```text
Cancel
```

and cancellation should abort the current request where supported by the HTTP stack.

---

# 19. Request construction order

When Send is invoked:

1. Load the selected request.
2. Compute its ancestor chain.
3. Build the effective variable scope.
4. Resolve `{{variables}}`.
5. Resolve URL/path variables.
6. Build query parameters.
7. Build user headers.
8. Resolve effective authentication.
9. Add authentication-generated headers/query parameters.
10. Build the request body.
11. Apply request settings.
12. Execute.
13. Capture response metadata and body.
14. Render the response.

Authentication-generated values should take precedence over automatically conflicting generated fields.

Avoid duplicate `Authorization` headers.

---

# 20. Response panel

The response pane must show at least:

```text
HTTP status
status text
elapsed time
response size
body
headers
cookies / Set-Cookie information
final URL after redirects
```

Provide tabs:

```text
Body
Headers
Cookies
Raw
```

## Body

Attempt presentation based on content type.

For JSON:

* pretty-print valid JSON;
* use JSON syntax highlighting if available.

For XML/HTML:

* optionally format;
* otherwise display text.

For text:

* display as-is.

For binary responses:

```text
Binary response
Content-Type: image/png
Size: 182 KB
```

Do not dump arbitrary binary bytes into the terminal.

## Headers

Display response headers preserving duplicates.

## Raw

Display an HTTP-oriented representation suitable for debugging:

```text
HTTP/1.1 200 OK
Content-Type: application/json
...

{...}
```

Sensitive request credentials must not appear here.

---

# 21. Runtime response persistence

Normal Send results are **runtime state**.

Do not write every HTTP response into `.kode.json`.

Postman `item.response[]` represents saved examples, not normal request history.

Imported `response[]` entries must be preserved.

A future `Save Response as Example` command may add a response to `item.response[]`, but that is not required for the initial feature.

---

# 22. Running folders and collections

`Run Folder` executes all descendant requests in collection order.

`Run Collection` executes the entire collection.

Use deterministic depth-first item order corresponding to the stored `item[]` hierarchy.

Default to sequential execution.

Show a run result panel:

```text
Collection Run

✓ GET  List Models              200   121 ms
✓ GET  Get Model                200    83 ms
✗ POST Create Fine Tuning Job   401   104 ms
✓ GET  Get Fine Tuning Jobs     200    92 ms

3 succeeded
1 failed
```

Selecting an entry should show that request's response.

Provide cancellation of the active run.

Do not implement parallel execution initially.

---

# 23. Scripts and Postman tests

Postman v2.1 can contain `event` definitions such as:

```text
prerequest
test
```

and JavaScript scripts.

The initial REST client does **not** need to implement the complete Postman JavaScript `pm.*` runtime.

However:

* imported `event` fields must be preserved;
* imported scripts must survive save/export unchanged;
* do not silently claim that scripts ran when they did not.

Do not add a functional `Scripts` editor that suggests scripts will execute unless an actual compatible script runtime is implemented.

A later feature can add Postman-style prerequest/test script execution.

For now, “REST API tests” means persisted and repeatable HTTP requests/runs.

---

# 24. Settings

Provide basic request/runtime settings, preferably mapped to `protocolProfileBehavior` when the Postman format supports the behavior and to Kode runtime settings otherwise.

Useful initial settings:

```text
Follow redirects
Request timeout
SSL certificate validation
Body handling for GET/HEAD
```

SSL verification must default to enabled.

Any setting not represented by Postman must live in Kode metadata, not as invented collection properties.

---

# 25. Cookies

Maintain an in-memory cookie jar for the workspace HTTP client.

Responses can populate it and subsequent requests can use it according to normal HTTP cookie rules.

The cookie jar is runtime state and should not be mixed into the Postman collection unless there is a deliberate later persistence feature.

Provide a way to clear the cookie jar.

---

# 26. Import

Support importing a `.postman_collection.json`.

Workflow:

```text
REST collection root
    → Import Postman Collection
```

Validate that the document resembles Postman Collection v2.1.

If a workspace already contains a collection, prompt before replacing it.

Preserve:

```text
info
item hierarchy
request definitions
variables
auth
descriptions
events/scripts
saved responses
protocolProfileBehavior
unknown valid fields
```

Do not flatten folders.

Do not normalize the imported JSON more than necessary.

Postman v3 multi-file YAML import is outside this feature.

---

# 27. Export

Support:

```text
Export Postman Collection
```

Export only:

```text
restApi.collection
```

and not the surrounding `.kode.json` data.

The result must be a valid Postman Collection v2.1 JSON document.

Do not emit Kode UI state.

Do not mutate `_postman_id` simply because the file is exported.

---

# 28. Editing and persistence

All collection editing operations update the workspace `.kode.json`.

Use the existing Kode workspace configuration persistence mechanism.

Do not create another configuration subsystem.

Writes should be atomic so that an interrupted write does not destroy `.kode.json`.

Avoid writing the file for every individual keystroke if the existing configuration architecture has commit/debounce semantics.

The in-memory collection is the editing source of truth, with controlled persistence to `.kode.json`.

---

# 29. Node creation

Creating a folder should initially generate:

```json
{
  "name": "New Folder",
  "item": []
}
```

Creating a request should initially generate something equivalent to:

```json
{
  "id": "<uuid>",
  "name": "New Request",
  "request": {
    "method": "GET",
    "header": [],
    "url": {
      "raw": ""
    }
  },
  "response": []
}
```

Request IDs should be stable UUIDs.

Duplicating a request must generate a new request ID.

Duplicating a folder must recursively duplicate the contents and regenerate request IDs contained within the copy.

---

# 30. Rename/move semantics

Renaming a request/folder changes only its `name`.

Moving a request/folder means removing the same object from one `item[]` and inserting it into another.

Moving must not reconstruct the request and accidentally lose unknown Postman fields.

The same rule applies to duplication except where IDs legitimately need regeneration.

---

# 31. Validation and error reporting

Distinguish between:

```text
editing validity
collection validity
execution validity
```

The UI must allow temporarily incomplete values while editing.

For example:

```text
https://
{{baseUrl}}/
:
```

must not cause the editor to crash or reject every keystroke.

Before Send, report actionable issues such as:

```text
URL is empty
Unsupported URL scheme
Variable {{baseUrl}} is unresolved
Body file does not exist
Authentication type is not executable
```

Network errors belong in the response pane:

```text
Connection refused
DNS lookup failed
TLS certificate error
Timeout
Request cancelled
```

Do not replace these with generic `"Request failed"` messages when the original cause is available.

---

# 32. Architecture

Keep the component separated into roughly these responsibilities, adapting names/packages to the existing Kode architecture rather than forcing these literal package names:

```text
rest/model
    Postman-compatible data representation

rest/storage
    integration with .kode.json
    import/export

rest/resolve
    variable inheritance
    substitution
    auth inheritance
    request materialization

rest/http
    HTTP execution
    cookies
    cancellation
    response model

rest/ui
    tree
    collection editor
    folder editor
    request editor
    response viewer
    run results
```

The important boundary is:

```text
UI != HTTP implementation
UI != JSON persistence
Postman model != resolved network request
```

The HTTP executor should consume a resolved immutable request object, not TUI widgets or raw tree nodes.

---

# 33. Suggested internal execution models

A useful resolved request representation would look conceptually like:

```text
ResolvedRequest
    method
    url
    headers
    body
    timeout
```

with no inheritance left in it.

Similarly:

```text
ExecutionResult
    request
    startedAt
    duration
    statusCode
    statusText
    headers
    cookies
    bodyBytes
    contentType
    finalUrl
    error
```

Do not persist these runtime types into the Postman collection.

---

# 34. HTTP client

Reuse an HTTP stack already present in Kode if suitable.

Otherwise choose a reasonably lightweight JVM HTTP client rather than implementing HTTP yourself.

Requirements:

```text
HTTP and HTTPS
redirect handling
request cancellation
connection reuse
binary request/response bodies
duplicate headers
cookies
timeouts
TLS validation
stream-safe body handling
```

Network activity must never block the TUI event loop.

---

# 35. Large responses

Do not allow an accidental multi-hundred-megabyte response to freeze the terminal.

Implement a configurable response preview/body memory limit.

When truncated, show explicitly:

```text
Response body truncated for display.
Received: 84.2 MB
Displayed: 10 MB
```

The exact default limit can follow existing Kode configuration conventions.

---

# 36. Search/filtering

If the REST tree already fits naturally with Kode's existing tree filtering, support filtering by:

```text
request name
folder name
HTTP method
URL
```

This is useful but secondary to the core feature.

Filtering must not mutate collection order.

---

# 37. Keyboard usability

The feature must be usable entirely from the keyboard.

At minimum ensure there is a keyboard route for:

```text
navigate REST tree
expand/collapse folder
open selected node
create request
create folder
rename
delete
switch request tabs
focus URL
send request
focus response pane
cancel request
```

Reuse Kode's existing keybinding infrastructure rather than hard-coding terminal escape sequences.

---

# 38. Mouse usability

Where Kode already supports mouse interaction:

```text
click node
expand/collapse
click Send
click tabs
click context/overflow action
resize response pane
```

should work consistently with the rest of Kode.

---

# 39. Secrets and logging

Explicitly test that these are never logged in plaintext by normal logging:

```text
Authorization headers
Bearer tokens
Basic passwords
API-key values
OAuth tokens
```

Logging the unresolved representation is acceptable:

```text
Authorization: Bearer {{bearerToken}}
```

Logging its resolved value is not.

---

# 40. Required automated tests

Implement tests for at least the following scenarios.

### Persistence and format

* Create an empty collection.
* Serialize into `.kode.json`.
* Reload it.
* Export as `.postman_collection.json`.
* Validate structural equivalence.
* Import a representative Postman v2.1 file.
* Save/reload/export without losing unsupported fields.

### Tree

* root request;
* root folder;
* nested folders;
* request creation;
* deletion;
* rename;
* reorder;
* move between folders;
* recursive folder duplication.

### Variables

Given:

```text
Collection:
    host = prod.example.com
    version = v1

Folder:
    host = test.example.com

Request:
    user = john
```

verify:

```text
https://{{host}}/{{version}}/users/{{user}}
```

becomes:

```text
https://test.example.com/v1/users/john
```

Also test:

* request overrides folder;
* folder overrides collection;
* nested folder resolution;
* disabled variables;
* unresolved variables;
* recursive substitutions;
* substitution cycles.

### Authentication

Test:

```text
collection bearer
folder inherits
request inherits
```

then:

```text
folder basic override
```

then:

```text
request noauth override
```

then:

```text
request bearer override
```

Verify the actual outgoing HTTP request.

### HTTP

Use a local embedded test server.

Test:

```text
GET
POST
PUT
PATCH
DELETE
query parameters
duplicate query parameters
headers
duplicate headers where supported
JSON body
form-urlencoded
multipart form
binary/file body
redirect
timeout
cancellation
cookies
non-2xx responses
binary responses
```

### UI

Where existing Kode UI testing infrastructure permits, test:

```text
tree selection changes editor
folder editor displays correct variables/auth
request editor binds correct model
Send creates execution
response appears in bottom pane
```

---

# 41. Acceptance scenario

The following workflow must work end to end.

The user creates a collection:

```text
Mistral AI API
```

and defines collection variables:

```text
baseUrl = https://api.mistral.ai
bearerToken = <token>
```

The collection Authorization is:

```text
Bearer Token
Token = {{bearerToken}}
```

The user creates:

```text
v1/
    models/
        List Models
```

`List Models` is:

```text
GET {{baseUrl}}/v1/models
```

and inherits authentication.

The left panel therefore resembles:

```text
▼ Mistral AI API
    ▼ v1
        ▼ models
            GET List Models
```

Opening `List Models` shows:

```text
GET | {{baseUrl}}/v1/models | Send
```

Authorization shows:

```text
Inherit auth from parent

Effective:
Bearer Token
from Mistral AI API
```

Variables show:

```text
baseUrl       https://api.mistral.ai     inherited
bearerToken   ******                     inherited
```

Pressing Send executes:

```text
GET https://api.mistral.ai/v1/models
Authorization: Bearer <resolved bearerToken>
```

and displays the returned:

```text
status
timing
size
headers
body
```

in the lower response pane.

Closing and reopening the workspace restores the collection from `.kode.json`.

Exporting the collection creates a valid Postman Collection v2.1 JSON document.

---

# 42. Explicit non-goals for this implementation

Do not expand the task into:

```text
Postman cloud/account synchronization
multiple collections per workspace
Postman environments
global Postman variables
Postman Vault
mock servers
monitors
interactive OAuth browser login
full Postman JavaScript pm.* runtime
Postman Collection 3.0 YAML
GraphQL schema exploration
gRPC
WebSocket clients
SOAP tooling
```

These may be implemented independently later.

The data model should nevertheless preserve compatible imported v2.1 fields it does not understand.

---

# 43. Implementation approach

Before coding:

1. Inspect the existing Kode sidebar/tree implementation.
2. Inspect how central content/editor panes are registered.
3. Inspect `.kode.json` loading/saving and workspace configuration models.
4. Inspect existing editable table/list widgets.
5. Inspect existing HTTP/network dependencies.
6. Inspect the existing command/keybinding/context-menu system.

Then implement this feature by extending those existing abstractions.

Do not create parallel UI, persistence, command, or event systems if Kode already has equivalents.

Implement the model/storage/resolution layer first, then the HTTP executor, then the tree/editor UI.

Keep commits logically separated so the feature can be reviewed incrementally.

The completed feature must build with the normal Kode build, have automated tests for the model/resolution/execution behavior, and must not regress existing editor functionality.

[1]: https://learning.postman.com/docs/use/use-collections/collections-schemas?utm_source=chatgpt.com "Postman Collections schemas | Postman Docs"
[2]: https://gist.github.com/hrhv/91d1515f3cdeef55f45c3ed61fedaa34 "JSON Schema for Postman Collection v2.1.0 · GitHub"
[3]: https://learning.postman.com/docs/use/send-requests/authorization/specifying-authorization-details?utm_source=chatgpt.com "Add API authorization details to requests in Postman | Postman Docs"
[4]: https://gist.github.com/hrhv/91d1515f3cdeef55f45c3ed61fedaa34?utm_source=chatgpt.com "JSON Schema for Postman Collection v2.1.0 · GitHub"

