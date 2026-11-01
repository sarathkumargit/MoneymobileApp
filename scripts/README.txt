MoneyMobile — Install the app on a phone
=========================================

ONE FILE FOR EVERY PHONE
  Mac:     setup-mac.command   (first time: right-click > Open > Open, afterwards double-click)
  Windows: setup-windows.bat   (if "Windows protected your PC" appears: More info > Run anyway)

  - New or factory-reset phone  -> installs the app, turns on full management, registers it to your shop.
  - Phone that's already set up -> only updates the app. Customer and lock status stay the same.

NEW / FACTORY-RESET PHONE — on the phone first
  1. Finish the welcome screens. Do NOT add a Google or Samsung account yet.
  2. Connect to the SAME Wi-Fi as the computer running the MoneyMobile website.
  3. Settings > About phone > Software information > tap "Build number" 7 times,
     then Settings > Developer options > turn ON "USB debugging".
  4. Put the customer's SIM in (this becomes the approved SIM).

THEN
  Plug the phone in, tap "Allow" on the phone, run the setup file, wait for DONE.
  The phone appears on the website by its IMEI — assign it to a customer.

AFTER SETUP
  - Overdue payments (app v1.6.0 and newer): the phone locks itself when the due date + grace days
    have passed, even with no internet or in airplane mode. It also shows a reminder 2 days and
    1 day before ("Your phone will lock on ..."), and the app shows the lock date. The phone needs
    to have been online once since the due date was set or changed.
  - Locked phone with no internet: website > customer > "30-min unlock code".
    The customer taps "Enter unlock code" on the phone. Works without Wi-Fi or SIM.
  - Play Store: once unlocked, Settings > Accounts > Add account > Google.

Keep this folder private: config.txt lets anyone register phones to your shop.
