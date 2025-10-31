/* eslint-disable max-len */
const { onValueWritten } = require("firebase-functions/v2/database");
const { initializeApp }  = require("firebase-admin/app");
const { getMessaging }   = require("firebase-admin/messaging");
const { getDatabase }    = require("firebase-admin/database");
const logger             = require("firebase-functions/logger");

initializeApp();

/** Derived thresholds (example): tweak per your calibrations */
const CALIB = {
  biodegradable: { empty: 32, full: 3 },
  recyclable:    { empty: 30, full: 5 },
  residual:      { empty: 30, full: 5 },
  hazardous:     { empty: 30, full: 5 },
};

function cmAtPercent(kind, percent /*0..100*/) {
  const { empty, full } = CALIB[kind] || CALIB["recyclable"];
  const t = (100 - percent) / 100; // 100% full → full cm; 0% → empty cm
  return full + t * (empty - full);
}

// ~90% full alert, ~20% for “emptied” return-to-service
const FULL_ALERT_CM_BY_KIND  = (k) => cmAtPercent(k, 90);  // ≈ 7–8 cm
const EMPTY_ALERT_CM_BY_KIND = (k) => cmAtPercent(k, 20);  // ≈ 27–28 cm

const MIN_DELTA_CM   = 0.5;         // ignore tiny changes
const COOLDOWN_MS    = 5 * 60 * 1000; // 5 minutes
const STATE_PATH     = "/alerts/state"; // where we store dedupe state

exports.binAlertsV2 = onValueWritten(
  {
    instance: "smartwastemanagement-41113-default-rtdb",
    region:   "us-central1",
    retry:    false,
    ref: "/Esp32smartWasteBin/{deviceId}/readings/waste_level/{kind}/level_cm",
  },
  async (event) => {
    const db = getDatabase();
    const { deviceId, kind } = event.params;

    const toNum = (v) => {
      if (v === null || v === undefined) return null;
      const n = Number(v);
      return Number.isNaN(n) ? null : n;
    };

    const prevCm = toNum(event.data.before.val());
    const currCm = toNum(event.data.after.val());
    if (currCm === null) return;

    // Don’t alert on first-ever write
    if (prevCm === null) {
      logger.info("binAlertsV2: first write, no alert", { deviceId, kind, currCm });
      return;
    }

    // Ignore jitter
    if (Math.abs(prevCm - currCm) < MIN_DELTA_CM) {
      logger.info("binAlertsV2: jitter ignored", { deviceId, kind, prevCm, currCm });
      return;
    }

    // Thresholds derived from calibration
    const FULL_ALERT_CM  = FULL_ALERT_CM_BY_KIND(kind);
    const EMPTY_ALERT_CM = EMPTY_ALERT_CM_BY_KIND(kind);

    // Crossings (smaller cm = fuller)
    const crossedToFull  = prevCm > FULL_ALERT_CM  && currCm <= FULL_ALERT_CM;
    const crossedEmptied = prevCm < EMPTY_ALERT_CM && currCm >= EMPTY_ALERT_CM;

    if (!crossedToFull && !crossedEmptied) return;

    // Load last notified state for dedupe/cooldown
    const stateRef = db.ref(`${STATE_PATH}/${deviceId}/${kind}`);
    const snap = await stateRef.get();
    const { lastState = null, lastTs = 0 } = snap.exists() ? snap.val() : {};
    const now = Date.now();
    const withinCooldown = now - (lastTs || 0) < COOLDOWN_MS;

    // Decide next state
    const nextState = crossedToFull ? "full" : crossedEmptied ? "emptied" : null;
    if (!nextState) return;

    // Dedupe: same state + cooldown → skip
    if (withinCooldown && nextState === lastState) {
      logger.info("binAlertsV2: deduped by cooldown", { deviceId, kind, nextState, currCm });
      return;
    }

    // Make & send the message
    const pretty = ({
      biodegradable: "Biodegradable ♻️",
      recyclable:    "Recyclable 🔁",
      residual:      "Residual 🗑️",
      hazardous:     "Hazardous ☣️",
    }[kind]) || kind;

    const notification =
      nextState === "full"
        ? { title: `${pretty} bin is full`, body: `Distance ${currCm.toFixed(1)}cm — please empty.` }
        : { title: `${pretty} bin emptied`, body: `Distance ${currCm.toFixed(1)}cm — back in service.` };

    await getMessaging().send({
      topic: "alerts",
      android: { priority: "high", notification: { channelId: "waste_notification_channel" } },
      notification,
      data: { deviceId, kind, level_cm: String(currCm), state: nextState },
    });

    // Persist new state for dedupe
    await stateRef.set({ lastState: nextState, lastTs: now, lastLevel: currCm });

    logger.info("binAlertsV2: sent", { deviceId, kind, nextState, currCm, FULL_ALERT_CM, EMPTY_ALERT_CM });
  }
);
