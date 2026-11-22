# VictorSMS editions and licensing

| Edition | Task | Contains | Licence of the distributed binary |
|---|---|---|---|
| **standard** | `assembleStandardRelease` | Apache-2.0 sources, Twilio Voice SDK (alias calls), Firebase push; **no** SIP engine | Apache-2.0 for our code; Twilio Voice SDK under Twilio's terms |
| **voip** (`.voip` app id) | `assembleVoipRelease` | Same sources + Linphone SDK (own SIP server calling); **no** Twilio Voice SDK, no Firebase | **GPLv3** (see `LICENSE-GPL-3.0`) because it links Linphone |

Our own source files stay Apache-2.0 (one-way compatible with GPLv3); the voip *binary* as a whole is GPLv3, so anyone distributing it must offer the corresponding source of the whole app. The editions can be installed side by side. Twilio SMS/REST features (no SDK) exist in both.
This is an engineering split, not legal advice: have the licences reviewed before publishing the voip edition, including Linphone's own third-party components.
Status: `standard` builds here; `voip` **could not be built here** (download.linphone.org is blocked from this environment), and `LinphoneSipEngine` is an uncompiled draft.
