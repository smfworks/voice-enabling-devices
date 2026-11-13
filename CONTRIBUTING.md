# Contributing a device page

This repository is a collection of sanitized how-tos. Each device lives in `devices/<slug>/` and is linked from the table in the top-level [README.md](README.md).

A new page should feel like the ones already here: what the device does, how a turn works, a diagram, setup on hardware that is already prepared, configuration with placeholders, then screenshots if the device has a screen.

## Folder

```
devices/<slug>/
  README.md          # the device page
  app/               # on-device source, when there is an app
  host/              # bridge, units, and helpers that run on your computer
  images/            # screenshots linked from the README
```

Use a short slug: `echo-dot`, `fire-tablet`, `echo-spot`, `echo-show-5`. Add a row to the device table. If the page is not ready, set the status to **Coming next week** and keep the placeholder honest. Do not invent setup steps.

## Device page

Use these headings, in this order:

1. **What it does** — one or two short paragraphs, including how a person starts a turn.
2. **How it works** — the turn, in prose. Name the files in this folder.
3. **Architecture** — a mermaid flowchart: device, Wi-Fi, the computer, the agent.
4. **Setup** — an already-prepared device only. Normal sideload and user-level services.
5. **Configuration** — a table of names and placeholders. Point at the example file.
6. **Screenshots** — images from `images/`, or a line that this device has no screen.
7. **Layout** — the tree inside the device folder.
8. **What this page does not contain** — the short list of things that stay off the page.

Open with the device name, **Status**, a link back to the collection README, and one sentence that rooting and unlocking are out of scope and are not documented on the page.

## Before you open a pull request

- Describe behavior the code actually has. Keep the reference code intact except to remove a secret or a personal path.
- Placeholders only: `<HOST_IP>`, `<TABLET_IP>`, `DOT_HOST`, `<YOUR_WEBHOOK_URL>`, `<YOUR_WEBHOOK_KEY>`. No real tokens, webhook URLs, ntfy topics, keystores, serial numbers, MAC addresses, SSIDs, LAN addresses, or personal home paths.
- No rooting, bootloader, exploit, or system/priv-app install steps.
- Do not name an employer or that employer's products.
- `~/some-project/` in a unit file is the install location a reader creates. It is not a path from the author's machine.
- Leave [LICENSE](LICENSE) as it is (MIT, SMF Works).
