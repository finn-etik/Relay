# Sync through Drime

How PAGE keeps notes, and LIST keeps shared lists, in step across devices
and people; written for whoever builds another end (a desktop app). The
shared code is `field/…/cloud/Sync.kt` (the rules, pure and tested) and
`Drime.kt` (the cloud calls); `PageSync.kt` and `ListSync.kt` carry the
rules out. PAGE is described first; LIST's differences follow at the end.

## What is in the cloud

One folder named `PAGE` at the top level of the user's Drime drive. In it,
one file per note: UTF-8 text, `.md` or `.txt` (`.markdown` is read too),
line ends `\n`. Nothing else: no database, no index, no hidden files. A
note's **file name is its identity**; its title is its first line.
Subfolders and other file types are ignored.

Anything that can put text files into that folder takes part: another
PAGE, the Drime web app, the Drime desktop client.

## What each device remembers

For every note name it has synced, three values from the moment the note
was last in step:

| Value | Meaning |
|-------|---------|
| `hash` | SHA-256 of the local text (hex) |
| `remoteId` | the Drime entry id of the cloud file |
| `stamp` | `updated_at + "/" + file_size` of that entry |

This memory is private to the device. It is never uploaded.

## One sync

1. Check that the `PAGE` folder still exists (list the top level). If it
   does not, stop: a missing folder must not read as "all notes deleted".
2. List the local notes (name, text) and the cloud folder (name, id,
   stamp), all pages.
3. For every name on either side or in the memory, decide:

| Here | Cloud | Remembered | Do |
|------|-------|------------|----|
| same as remembered | same as remembered | yes | nothing |
| changed | same | yes | upload (replaces the cloud file) |
| same | changed | yes | download |
| changed | changed | yes | **conflict** |
| exists | exists | no | **conflict** |
| exists | missing | no | upload |
| missing | exists | no | download |
| changed | missing | yes | upload |
| same | missing | yes | delete here (it was deleted elsewhere) |
| missing | changed | yes | download |
| missing | same | yes | move the cloud file to the trash (it was deleted here) |
| missing | missing | yes | forget |

   "Changed" here means: the text's hash differs from `hash`. "Changed" in
   the cloud means: the entry id or the stamp differs.

4. **Conflict:** download the cloud text. If it equals the local text,
   only remember it. Otherwise keep both: the local text is saved as
   `Name (conflict yyyy-MM-dd HHmm).md` (a new note, uploaded by the next
   sync) and the cloud text takes the name.
5. Before anything is deleted: if the plan would delete more than three
   notes and more than half of a side, stop and change nothing.
6. Update the memory after every step, so an interrupted sync resumes.
   After uploads, list the folder once more and remember the uploaded
   files' real stamps.

Deletions in the cloud go to Drime's trash, never "forever".

## Drime calls used

Base `https://app.drime.cloud/api/v1`, header `Authorization: Bearer
<personal access token>` (Drime: Account Settings, Developers).

| For | Call |
|-----|------|
| Check the token | `GET /cli/loggedUser` |
| List a folder | `GET /drive/file-entries?workspaceId=0&perPage=100&page=N[&folderId=<folder hash>]` |
| Create the folder | `POST /folders?workspaceId=0` `{"name":"PAGE","parentId":null}` |
| Upload | `POST /uploads` (multipart: `file`, `parentId`, `workspaceId`, `lastModified`) |
| Download | `GET /file-entries/download/<file hash>` (may redirect to storage; do not send the token there) |
| Rename | `PUT /file-entries/<id>` `{"name":"…"}` |
| Trash | `POST /file-entries/delete` `{"entryIds":[…],"deleteForever":false}` |

Drime's documentation does not say what an upload does when the folder
already holds a file of that name. PAGE therefore handles both: if the
upload comes back as a new entry, the old entry is moved to the trash, and
if Drime gave the new one another name, it is renamed.

## Known limits

- A rename is a delete plus a new note (the name is the identity).
- Two devices editing the same note between syncs produce a conflict copy,
  not a merge.
- Sync runs when PAGE opens and when a note is closed, not in the
  background.

## LIST: shared lists

A **Drime space** is any Drime folder holding one Markdown file per list
(the format is in `TaskFile.kt`: a `# Title` line and `- [ ]` task lines
with an `id:` marker). The phone keeps a copy of the files in app storage
and syncs it with the folder by the rules above, with these differences:

- **Conflicts are merged, not kept twice.** Where both sides changed a
  list, the two are merged task by task (by task id: done wins, steps are
  united, new tasks from both sides are kept) and the result is uploaded.
- **Two files of one name** in the folder (two phones uploading at the
  same moment) are merged into one the same way; the extras go to the
  trash.
- **A list edited while a sync is on the network** is merged with what
  the sync brings, never overwritten.
- **When:** a few seconds after every edit, on opening LIST, and once a
  minute while it is on screen.

Because merging unites tasks, a task one person deletes while the other
edits the same list at the same time can come back. Ticking it off does
not have that problem.

**Two people, one space.** Either both phones use the same Drime account
(each pastes a token of that account), or the owner shares the folder
with the other's account: `POST /file-entries/<folder id>/share` with
`{"emails":["…"],"permissions":["view","edit","download"]}`. The other
finds it with `GET /drive/file-entries?sharedOnly=true` and adds it as a
space.
