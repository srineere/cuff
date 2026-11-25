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

## What it should do

Make your strap buzz for your phone's notifications and calls. It has never
been run against a real strap, so "should" is doing real work in that
sentence — you are the first person to try it.

Two unknowns it answers, in order. Whether a second app can get the strap's
pairing key while the Zepp app still owns the strap. And then whether the
strap will actually take a command from something that is not Zepp.

## What to do

1. Install the APK. Android will warn you it is from an unknown source; that
   is expected for anything not from the Play Store.
2. Open **Cuff Helio** → **Device** tab → **Amazfit Helio Strap**.
3. Sign in with your Zepp **email and password**. Google or Apple sign-in
   will not work inside an app — if that is all your account has, you can
   add an email password in the Zepp app, or stop here and say so.
4. Tap **I have signed in — find my strap**. Tell me what it says.
5. If it said **Paired**: go back to **Device** and tap **Buzz the band**,
   with the strap on your wrist.
6. If that buzzed: turn the relay on from the **Alerts** tab, allow the
   permissions it asks for, pick an app or two under **Apps**, and send
   yourself a message.

## What I need back

Whatever happened, at each step you got to. Most useful:

- **Did step 4 say "Paired"?** If not, the exact words — "no Helio Strap on
  it" and "did not give out its pairing key" mean quite different things.
- **Did step 5 buzz?** If nothing happened, roughly how long you waited.
- **Did step 6 buzz, and how long after the notification?**
- Anything that looked wrong, and the wording of any failure in the
  **Alerts** list. Those sentences are deliberately specific.
- If it crashes, **Device → About** has a crash log you can copy out.

Keep Zepp installed. Whether the two can share the strap is part of what
this is testing — if buzzing only works with Zepp force-stopped, that is a
useful answer too, not a failure.

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
