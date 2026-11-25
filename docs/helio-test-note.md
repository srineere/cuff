# Helio Strap spike — note for the tester

What to send someone with an Amazfit Helio Strap, alongside the APK. The
build is `cuff-helio-test.apk`, which is gitignored and rebuilt with
`./gradlew :app:assembleHelio`.

---

## What this is

Cuff is an Android app that makes a screenless fitness band buzz for your
phone's calls and notifications. It works on the Fitbit Air today. This build
is an experiment in making it work on the **Amazfit Helio Strap** as well,
and it needs someone who owns one.

It installs as **"Cuff Helio"**, a separate app. It will not touch anything
else on your phone.

## What it does NOT do yet

**It will not buzz your strap.** That part is not written. Please do not
judge it on that.

What it is testing is the step *before* buzzing: whether a second app can get
hold of the strap's pairing key at all while the Zepp app still owns the
strap. Nobody knows the answer, and it decides whether the rest is worth
building.

## What to do

1. Install the APK. Android will warn you it is from an unknown source; that
   is expected for anything not from the Play Store.
2. Open **Cuff Helio** → **Device** tab → **Amazfit Helio Strap**.
3. Sign in with your Zepp **email and password**. Google or Apple sign-in
   will not work inside an app — if that is all your account has, you can
   add an email password in the Zepp app, or stop here and say so.
4. Tap **I have signed in — find my strap**.
5. Tell me what the screen says.

## What I need back

Just the outcome of step 5, in whatever words:

- **"Paired"** plus the strap address it shows — the best answer, and the
  one that means this is worth building.
- **"That Zepp account has no Helio Strap on it"** — the account is fine but
  the strap is not listed on it.
- **"Zepp knows your strap but did not give out its pairing key"** — the
  interesting failure, and the one I half expect. It means Zepp withholds the
  key while its own app holds the strap.
- Anything else, including a crash. If it crashes, **Device → About** has a
  crash log you can copy out and send.

## What it does with your Zepp account

Worth reading before you sign in, because it is your account.

- You sign in on **Zepp's own page**, inside a web view. The app never sees
  your password.
- It then makes **one request**, to Zepp, asking which devices are on your
  account. That is the only network call this app makes, ever.
- It keeps the strap's pairing key, your Zepp session token and your user id
  in the app's private storage, so it does not have to ask twice. The sign-in
  cookies are deleted straight after.
- Nothing about your health, your steps, your sleep or your phone is sent
  anywhere. Nothing goes to me.
- Deleting the app deletes all of it. There is no account to close.

If any of that is not something you want to do with your Zepp account, say
no — it is a reasonable thing to say no to, and the alternative is that I
find someone else.

## Afterwards

Uninstall it whenever you like; it takes everything with it. The Zepp app and
your strap are not changed by any of this — this build only reads.
