# Turning this into a plugin — exact steps

You have **source code** (text files). A server needs a **.jar file**. GitHub can build that jar for
you for free, with nothing installed on your computer. Follow these steps exactly, in order.

## Step 1 — Create the repository
1. Go to github.com, sign in (or make a free account).
2. Click the **+** in the top-right corner → **New repository**.
3. Name it `GodGear`. Leave everything else default. Click **Create repository**.

## Step 2 — Upload the files
1. On the new repo's page, click **uploading an existing file** (a blue link in the middle of the page).
2. Unzip `GodGear.zip` on your computer first. Open the unzipped `GodGear` folder.
3. Select **everything inside that folder** (`src`, `.github`, `tools`, `build.gradle.kts`,
   `settings.gradle.kts`, `GodGearPack.zip`, `.gitignore`, all the `.md` files — select all, Ctrl+A / Cmd+A)
   and drag the whole selection into the browser upload box.
   ⚠️ Drag the **contents** of the `GodGear` folder, not the `GodGear` folder itself.
4. Scroll down, click **Commit changes**.
5. Confirm it worked: on the repo's main page you should see a folder called `.github` in the file list.
   If you don't see it, reload the page.

## Step 3 — Let GitHub build the jar
1. Click the **Actions** tab (top of the repo page, next to "Code", "Issues", "Pull requests").
2. You'll see a run named **Build GodGear** with a yellow dot (running) or green check (done).
   If nothing is there, click **workflows** on the left → **Build GodGear** → **Run workflow** button.
3. Wait 1–3 minutes for the yellow dot to turn into a green check (✅). Click the run to open it.

## Step 4 — Download the jar ("where are Artifacts?")
1. Still inside that same run page (the one you opened in Step 3.3), **scroll all the way down**.
2. Below the log boxes there is a section literally titled **Artifacts**, with one entry:
   **GodGear-jar**. Click it — this downloads a `.zip`.
3. Unzip that download. Inside is `GodGear-1.0.0.jar`. That is the finished plugin.

If Step 3 shows a red ❌ instead of a green check: click the run, click the red step, copy the error
text (it's red), and send it to me — I'll fix the code.

## Step 5 — Install it
1. Copy `GodGear-1.0.0.jar` into your server's `plugins` folder.
2. Your server must be **Paper 26.1.2** running **Java 25**, or the plugin won't load.
3. Restart the server. In-game, run `/godrecipes` to confirm it loaded.

---

## Alternative: build it yourself (skip GitHub)
Only do this if you'd rather not use GitHub.
1. Install **JDK 25** (search "Temurin 25" on adoptium.net).
2. Install **Gradle 9+** (gradle.org/install).
3. Open a terminal in the unzipped `GodGear` folder, run: `gradle build`
4. The jar is at `build/libs/GodGear-1.0.0.jar`.

---

## The custom textures (resource pack)
`GodGearPack.zip` (already inside the download) gives every god item its own icon and its own look
when worn. Without it on the client, god items show as purple/black boxes.

1. Upload `GodGearPack.zip` somewhere that gives a **direct download link** — easiest is a GitHub
   **Release** on the same repository (repo page → **Releases** → **Create a new release** → attach
   the zip → Publish → right-click the attached file → Copy link).
2. In `plugins/GodGear/config.yml` (created after the plugin's first run) set:
   ```yaml
   resource-pack:
     enabled: true
     url: "https://your-direct-link/GodGearPack.zip"
     sha1: "fa08dd612abe5cf8f5d8081698f5dbed81264919"
   ```
   That SHA-1 is already correct for the zip included in this download — only change it if you
   re-run `tools/make_pack.py` and it prints a new one.
3. Don't want textures at all? Set `custom-models: false` instead (restart required).

## The ability key
Servers can't detect a specific key like "X" being pressed — they can only see a few fixed actions.
This plugin reuses the **"Swap Item With Offhand"** action (F by default) for weapon abilities.
Each player who wants to use it should go to:
**Options → Controls → Key Binds → Swap Item With Offhand → rebind to X** (or any key they like).
Then that key = ability 3, and Shift + that key = ability 4, on god weapons.
`ability-key-label: "X"` in config.yml only controls what the item lore and action bar *display* —
change it to match whatever key your players actually bind.
