// Android orientation patch.
//
// History: this used to force `landscape` because the desktop workbench layout
// did not fit a portrait phone. The mobile adaptation now ships a bottom tab bar
// for one-hand portrait use, so the activity must be free to rotate: we set
// `fullSensor` (all four orientations, sensor-driven) instead of locking it.
import fs from 'fs';
import path from 'path';

const manifestPath = path.resolve('src-tauri/gen/android/app/src/main/AndroidManifest.xml');
const ORIENTATION = 'fullSensor';

if (!fs.existsSync(manifestPath)) {
  console.log('⚠️ [SPICE] AndroidManifest.xml not found. (If you are compiling for Android, please run "pnpm tauri android init" first to generate the Android project).');
  process.exit(0);
}

try {
  let content = fs.readFileSync(manifestPath, 'utf8');

  if (content.includes(`android:screenOrientation="${ORIENTATION}"`)) {
    console.log(`✅ [SPICE] Android screenOrientation is already set to ${ORIENTATION}.`);
    process.exit(0);
  }

  const activityMatch = /<activity\s+[^>]*android:name="\.MainActivity"[^>]*>/i;
  const match = content.match(activityMatch);

  if (match) {
    let activityTag = match[0];
    if (activityTag.includes('android:screenOrientation')) {
      activityTag = activityTag.replace(/android:screenOrientation="[^"]*"/, `android:screenOrientation="${ORIENTATION}"`);
    } else {
      activityTag = activityTag.replace(/>$/, ` android:screenOrientation="${ORIENTATION}">`);
    }
    content = content.replace(match[0], activityTag);
    fs.writeFileSync(manifestPath, content, 'utf8');
    console.log(`🚀 [SPICE] AndroidManifest.xml screenOrientation set to ${ORIENTATION} (portrait + landscape supported).`);
  } else {
    console.log('❌ [SPICE] Could not find .MainActivity activity node in AndroidManifest.xml');
    process.exit(1);
  }
} catch (err) {
  console.error('❌ [SPICE] Error patching AndroidManifest.xml:', err);
}
