// Runs the server's Gradle wrapper on any OS: node scripts/gradlew.mjs <tasks...>
// Windows needs gradlew.bat (through a shell); macOS and Linux use ./gradlew.
import { spawnSync } from "node:child_process";

const windows = process.platform === "win32";
const wrapper = windows ? "gradlew.bat" : "./gradlew";

const { status } = spawnSync(wrapper, process.argv.slice(2), {
  cwd: new URL("../server/", import.meta.url),
  stdio: "inherit",
  shell: windows,
});

process.exit(status ?? 1);
