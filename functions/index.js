/* eslint-disable max-len */
const { onValueWritten } = require("firebase-functions/v2/database");
const { initializeApp } = require("firebase-admin/app");
const { getMessaging } = require("firebase-admin/messaging");
const logger = require("firebase-functions/logger");

initializeApp();

const toPct = (d) => {
  const max = 18.0, min = 2.0;
  return Math.max(0, Math.min(100, Math.round(((max - d) / (max - min)) * 100)));
};
const crossedUp = (prev, curr, critical = 90) => (prev ?? -1) < critical && curr >= critical;

exports.binAlerts = onValueWritten(
  {
    // 👇 YOUR US-CENTRAL DEFAULT RTDB INSTANCE
    instance: "smartwastemanagement-41113-default-rtdb",
    // 👇 Fire when either distance field changes
    ref: "/ESP32_BiodegradableData/latestEntry/{key}",
    // Compute region for the function itself (optional, but good to keep near DB)
    region: "us-central1",
    retry: false,
  },
  async (event) => {
    const key = event.params.key;
    if (key !== "distanceCmBio" && key !== "distanceCmNonBio") return;

    const before = Number(event.data.before.val());
    const after  = Number(event.data.after.val());
    if (Number.isNaN(after)) return;

    const prev = Number.isNaN(before) ? null : toPct(before);
    const curr = toPct(after);
    const pretty = key === "distanceCmBio" ? "Biodegradable" : "Recyclable";

    logger.info("binAlerts", { key, prev, curr });

    if (crossedUp(prev, curr, 90)) {
      await getMessaging().send({
        topic: "alerts",
        notification: {
          title: `${pretty} bin is full`,
          body: `Now at ${curr}% — please empty.`,
        },
        android: { priority: "high", notification: { channelId: "waste_notification_channel" } },
        data: { bin: pretty.toLowerCase(), pct: String(curr) },
      });
    }
  }
);


//firebase deploy --only functions:binAlerts -P smartwastemanagement-41113
//pag idedeploy ulit
//firebase functions:delete binAlerts --region us-central1 -P smartwastemanagement-41113
//firebase functions:list -P smartwastemanagement-41113   # verify


