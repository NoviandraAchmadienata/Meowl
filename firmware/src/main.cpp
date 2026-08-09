/*
 * 🐱 Meowl — Firmware ESP32 Utama
 * Perangkat IoT Voicemail Asinkron Berbasis ESP32 untuk Pasangan LDR
 * 
 * Hardware Pinout:
 * - INMP441 (I2S Mic RX): SCK=14, WS=15, SD=32, L/R=GND
 * - MAX98357A (I2S Amp TX): BCLK=26, LRC=25, DIN=33
 * - OLED SSD1306 0.96" (I2C): SDA=21, SCL=22
 * - MicroSD Modul (SPI): CS=5, MOSI=23, MISO=19, SCK=18
 * - Tombol PTT: GPIO 12 (INPUT_PULLUP)
 * - Tombol Tengah: GPIO 13 (INPUT_PULLUP)
 */

#include <Arduino.h>
#include <WiFi.h>
#include <HTTPClient.h>
#include <WiFiClient.h>
#include <driver/i2s.h>
#include <SPI.h>
#include <SD.h>
#include <Wire.h>
#include <Adafruit_GFX.h>
#include <Adafruit_SSD1306.h>
#include <OneButton.h>
#include <PubSubClient.h>
#include <WiFiManager.h>
#include <Preferences.h>
#include <time.h>

// ─── PIN DEFINITIONS ────────────────────────────────────────────────────────
#define PIN_PTT           12
#define PIN_BUTTON_CENTER 13

// I2S Microphone INMP441 (I2S PORT 0 - RX)
#define I2S_MIC_PORT      I2S_NUM_0
#define I2S_MIC_SCK       14
#define I2S_MIC_WS        15
#define I2S_MIC_SD        32

// I2S Amplifier MAX98357A (I2S PORT 1 - TX)
#define I2S_SPK_PORT      I2S_NUM_1
#define I2S_SPK_BCLK      26
#define I2S_SPK_LRC       25
#define I2S_SPK_DIN       33

// SPI MicroSD Card
#define SD_CS_PIN         5

// OLED Display (128x64 I2C)
#define SCREEN_WIDTH      128
#define SCREEN_HEIGHT     64
#define OLED_RESET        -1
Adafruit_SSD1306 display(SCREEN_WIDTH, SCREEN_HEIGHT, &Wire, OLED_RESET);

// ─── GLOBAL OBJECTS & STATE ──────────────────────────────────────────────────
OneButton buttonCenter(PIN_BUTTON_CENTER, true);
WiFiClient espClient;
PubSubClient mqttClient(espClient);
Preferences pref;

enum DeviceState {
  STATE_BOOT,
  STATE_IDLE,
  STATE_RECORDING,
  STATE_UPLOADING,
  STATE_NOTIFY,
  STATE_PLAYING,
  STATE_PAUSED,
  STATE_PING,
  STATE_SETUP
};

DeviceState currentState = STATE_BOOT;
DeviceState previousState = STATE_IDLE;

char myID[32]      = "andra_cat";
char targetID[32]  = "partner_cat";
char vpsHost[64]   = "139.59.220.150"; // VPS Server IP
int  vpsPort       = 3000;
int  mqttPort      = 1883;
int  speakerGain   = 80;

bool isPlaying = false;
bool isPaused  = false;
File currentAudioFile;
unsigned long stateTimer = 0;
unsigned long blinkTimer = 0;

// ─── FUNCTION PROTOTYPES ────────────────────────────────────────────────────
void initOLED();
void renderOLED();
void initI2SMic();
void initI2SSpeaker();
void initSDCard();
void initWiFiManager();
void initMQTT();
void mqttCallback(char* topic, byte* payload, unsigned int length);

void handleSingleClick();
void handleDoubleClick();
void handleTripleClick();
void handleLongPress();
void checkResetOnBoot();

void startRecording();
void stopRecordingAndUpload();
void downloadAudioFile(String filename);
void playAudio();
void pauseAudio();
void skipAudio();
void sendPing();
void garbageCollectSD();
int getUnreadMessageCount();

// ─── WAV HEADER STRUCT ──────────────────────────────────────────────────────
struct WAVHeader {
  char riff[4] = {'R', 'I', 'F', 'F'};
  uint32_t fileSize;
  char wave[4] = {'W', 'A', 'V', 'E'};
  char fmt[4]  = {'f', 'm', 't', ' '};
  uint32_t fmtSize = 16;
  uint16_t audioFormat = 1; // PCM
  uint16_t numChannels = 1; // Mono
  uint32_t sampleRate = 16000;
  uint32_t byteRate = 32000;
  uint16_t blockAlign = 2;
  uint16_t bitsPerSample = 16;
  char data[4] = {'d', 'a', 't', 'a'};
  uint32_t dataSize;
};

// ─── SETUP ──────────────────────────────────────────────────────────────────
void setup() {
  Serial.begin(115200);
  pinMode(PIN_PTT, INPUT_PULLUP);
  pinMode(PIN_BUTTON_CENTER, INPUT_PULLUP);

  initOLED();

  // 0. Check Hold-on-Boot Factory Reset (Tahan >3s saat Power-ON)
  checkResetOnBoot();

  // Read preferences from NVS Flash
  pref.begin("meowl", true);
  String storedMyID = pref.getString("my_id", "andra_cat");
  String storedTargetID = pref.getString("target_id", "partner_cat");
  speakerGain = pref.getInt("gain", 80);
  pref.end();
  storedMyID.toCharArray(myID, 32);
  storedTargetID.toCharArray(targetID, 32);

  // Initialize Hardware
  initSDCard();
  initI2SMic();
  initI2SSpeaker();

  // OneButton Event Listener Attachment
  buttonCenter.attachClick(handleSingleClick);
  buttonCenter.attachDoubleClick(handleDoubleClick);
  buttonCenter.attachMultiClick([]() {
    int clicks = buttonCenter.getNumberClicks();
    if (clicks == 3) handleTripleClick();
    else if (clicks == 5) {
      Serial.println("⚠️ 5x Klik: Reset Wi-Fi & Credentials Triggered!");
      WiFiManager wm;
      wm.resetSettings();
      ESP.restart();
    }
  });
  buttonCenter.setPressMs(1500);
  buttonCenter.attachLongPressStart(handleLongPress);

  // Network & Cloud Setup
  initWiFiManager();
  
  // NTP Clock Sync
  configTime(7 * 3600, 0, "pool.ntp.org", "time.nist.gov");

  initMQTT();

  currentState = STATE_IDLE;
  Serial.println("✅ Meowl ESP32 Firmware Ready!");
}

// ─── MAIN LOOP ──────────────────────────────────────────────────────────────
void loop() {
  buttonCenter.tick();

  if (WiFi.status() == WL_CONNECTED) {
    if (!mqttClient.connected()) {
      initMQTT();
    }
    mqttClient.loop();
  }

  // Handle PTT Side Button
  static bool pttLastState = HIGH;
  bool pttCurrentState = digitalRead(PIN_PTT);
  if (pttLastState == HIGH && pttCurrentState == LOW) {
    startRecording();
  } else if (pttLastState == LOW && pttCurrentState == HIGH) {
    stopRecordingAndUpload();
  }
  pttLastState = pttCurrentState;

  // Non-blocking Audio Playback Engine
  if (isPlaying && !isPaused && currentAudioFile) {
    uint8_t buffer[512];
    int bytesRead = currentAudioFile.read(buffer, sizeof(buffer));
    if (bytesRead > 0) {
      // Software Volume Gain Scaling
      int16_t* samples = (int16_t*)buffer;
      int sampleCount = bytesRead / 2;
      for (int i = 0; i < sampleCount; i++) {
        int32_t val = (samples[i] * speakerGain) / 100;
        samples[i] = (int16_t)constrain(val, -32768, 32767);
      }
      size_t bytesWritten;
      i2s_write(I2S_SPK_PORT, buffer, bytesRead, &bytesWritten, portMAX_DELAY);
    } else {
      currentAudioFile.close();
      isPlaying = false;
      Serial.println("✅ Playback finished");
      currentState = STATE_IDLE;
    }
  }

  // State Timer Auto-Transitions
  if (currentState == STATE_PING && millis() - stateTimer > 5000) {
    currentState = previousState;
  }

  // Periodic SD Ephemeral Cleanup (Every 10 minutes during Idle)
  static unsigned long lastGCTime = 0;
  if (currentState == STATE_IDLE && millis() - lastGCTime > 600000) {
    lastGCTime = millis();
    garbageCollectSD();
  }

  renderOLED();
}

// ─── ONEBUTTON HANDLERS ──────────────────────────────────────────────────────
void handleSingleClick() {
  Serial.println("🔘 1x Click: Play / Replay");
  if (currentState == STATE_PAUSED) {
    isPaused = false;
    currentState = STATE_PLAYING;
  } else {
    playAudio();
  }
}

void handleDoubleClick() {
  Serial.println("🔘 2x Click: Pause / Resume");
  pauseAudio();
}

void handleTripleClick() {
  Serial.println("🔘 3x Click: Skip Audio");
  skipAudio();
}

void handleLongPress() {
  Serial.println("🔘 Long Press: Kirim Ping Hati");
  sendPing();
}

// ─── FACTORY RESET ON BOOT ───────────────────────────────────────────────────
void checkResetOnBoot() {
  if (digitalRead(PIN_BUTTON_CENTER) == LOW) {
    display.clearDisplay();
    display.setTextSize(1);
    display.setTextColor(SSD1306_WHITE);
    display.setCursor(10, 20);
    display.println("Tahan 3 Detik...");
    display.setCursor(10, 35);
    display.println("Untuk Reset Wi-Fi");
    display.display();
    
    delay(3000);
    if (digitalRead(PIN_BUTTON_CENTER) == LOW) {
      display.clearDisplay();
      display.setCursor(20, 25);
      display.println("FACTORY RESET!");
      display.display();
      
      WiFiManager wm;
      wm.resetSettings();
      pref.begin("meowl", false);
      pref.clear();
      pref.end();
      delay(1500);
      ESP.restart();
    }
  }
}

// ─── AUDIO RECORDING & UPLOAD ────────────────────────────────────────────────
void startRecording() {
  Serial.println("🎙️ Recording started...");
  currentState = STATE_RECORDING;

  if (SD.exists("/rec_temp.wav")) SD.remove("/rec_temp.wav");
  File recFile = SD.open("/rec_temp.wav", FILE_WRITE);
  if (!recFile) {
    Serial.println("❌ Gagal membuat temp file SD Card!");
    currentState = STATE_IDLE;
    return;
  }

  WAVHeader header;
  recFile.write((uint8_t*)&header, sizeof(WAVHeader));
  uint32_t totalBytes = 0;

  unsigned long startTime = millis();
  while (digitalRead(PIN_PTT) == LOW && (millis() - startTime < 15000)) { // Max 15 sec
    uint8_t buf[512];
    size_t bytesRead;
    i2s_read(I2S_MIC_PORT, buf, sizeof(buf), &bytesRead, portMAX_DELAY);
    if (bytesRead > 0) {
      recFile.write(buf, bytesRead);
      totalBytes += bytesRead;
    }
  }

  // Update WAV header sizes
  header.fileSize = totalBytes + sizeof(WAVHeader) - 8;
  header.dataSize = totalBytes;
  recFile.seek(0);
  recFile.write((uint8_t*)&header, sizeof(WAVHeader));
  recFile.close();

  Serial.printf("🎙️ Rekaman selesai (%d KB)\n", totalBytes / 1024);
}

void stopRecordingAndUpload() {
  if (currentState != STATE_RECORDING) return;
  currentState = STATE_UPLOADING;
  renderOLED();

  File recFile = SD.open("/rec_temp.wav", FILE_READ);
  if (!recFile) {
    currentState = STATE_IDLE;
    return;
  }

  HTTPClient http;
  String url = "http://" + String(vpsHost) + ":" + String(vpsPort) + "/upload/" + String(targetID);
  http.begin(url);
  http.addHeader("Content-Type", "audio/wav");

  int httpCode = http.sendRequest("POST", &recFile, recFile.size());
  recFile.close();
  http.end();

  if (httpCode == 200 || httpCode == 201) {
    Serial.println("✅ HTTP Upload Sukses!");
    // Send MQTT notification signal
    String topic = "meowl/" + String(targetID) + "/notify";
    String payload = "{\"from\":\"" + String(myID) + "\",\"file\":\"baru_" + String(time(NULL)) + ".wav\"}";
    mqttClient.publish(topic.c_str(), payload.c_str());
  } else {
    Serial.printf("❌ HTTP Upload Gagal, code: %d\n", httpCode);
  }

  currentState = STATE_IDLE;
}

// ─── AUDIO DOWNLOAD & PLAYBACK ───────────────────────────────────────────────
void downloadAudioFile(String filename) {
  HTTPClient http;
  String url = "http://" + String(vpsHost) + ":" + String(vpsPort) + "/download/" + String(myID) + "/" + filename;
  http.begin(url);

  int httpCode = http.GET();
  if (httpCode == HTTP_CODE_OK) {
    String localPath = "/" + filename;
    File file = SD.open(localPath, FILE_WRITE);
    if (file) {
      http.writeToStream(&file);
      file.close();
      Serial.println("💾 Download & Simpan SD Card Sukses: " + localPath);
    }
  }
  http.end();
  currentState = STATE_NOTIFY;
}

void playAudio() {
  // Find first unread message 'baru_*.wav'
  File root = SD.open("/");
  String fileToPlay = "";

  File file = root.openNextFile();
  while (file) {
    String name = String(file.name());
    if (name.startsWith("baru_") || name.startsWith("/baru_")) {
      fileToPlay = name;
      break;
    }
    file = root.openNextFile();
  }

  // If no unread, find last read 'lama_*.wav' (Replay)
  if (fileToPlay == "") {
    root.rewindDirectory();
    file = root.openNextFile();
    while (file) {
      String name = String(file.name());
      if (name.startsWith("lama_") || name.startsWith("/lama_")) {
        fileToPlay = name;
        break;
      }
      file = root.openNextFile();
    }
  }

  if (fileToPlay != "") {
    if (!fileToPlay.startsWith("/")) fileToPlay = "/" + fileToPlay;
    currentAudioFile = SD.open(fileToPlay, FILE_READ);
    if (currentAudioFile) {
      // Skip WAV header (44 bytes)
      currentAudioFile.seek(44);
      isPlaying = true;
      isPaused = false;
      currentState = STATE_PLAYING;

      // Rename baru_*.wav -> lama_*.wav
      if (fileToPlay.contains("baru_")) {
        String newName = fileToPlay;
        newName.replace("baru_", "lama_");
        currentAudioFile.close();
        SD.rename(fileToPlay, newName);
        currentAudioFile = SD.open(newName, FILE_READ);
        currentAudioFile.seek(44);
      }
    }
  }
}

void pauseAudio() {
  if (currentState == STATE_PLAYING) {
    isPaused = true;
    currentState = STATE_PAUSED;
  } else if (currentState == STATE_PAUSED) {
    isPaused = false;
    currentState = STATE_PLAYING;
  }
}

void skipAudio() {
  if (isPlaying) {
    currentAudioFile.close();
    isPlaying = false;
    isPaused = false;
    playAudio(); // Play next
  }
}

void sendPing() {
  String topic = "meowl/" + String(targetID) + "/ping";
  String payload = "{\"from\":\"" + String(myID) + "\"}";
  mqttClient.publish(topic.c_str(), payload.c_str());
  Serial.println("💗 Ping Hati MQTT Sent!");
}

int getUnreadMessageCount() {
  File root = SD.open("/");
  int count = 0;
  File file = root.openNextFile();
  while (file) {
    String name = String(file.name());
    if (name.startsWith("baru_") || name.startsWith("/baru_")) count++;
    file = root.openNextFile();
  }
  return count;
}

void garbageCollectSD() {
  File root = SD.open("/");
  time_t now = time(NULL);
  File file = root.openNextFile();
  while (file) {
    String name = String(file.name());
    if (name.startsWith("lama_") || name.startsWith("/lama_")) {
      // Extract timestamp from filename 'lama_1690000000.wav'
      int tsIndex = name.indexOf('_') + 1;
      long fileTs = name.substring(tsIndex, name.indexOf('.')).toInt();
      if (fileTs > 0 && (now - fileTs > 86400)) {
        SD.remove(name);
        Serial.println("🗑️ GC Ephemeral: Deleted " + name);
      }
    }
    file = root.openNextFile();
  }
}

// ─── CLOUD & NETWORK INIT ───────────────────────────────────────────────────
void initWiFiManager() {
  WiFiManager wm;
  wm.setConnectTimeout(30);

  WiFiManagerParameter customMyID("myid", "ID Perangkat Ini", myID, 32);
  WiFiManagerParameter customTargetID("targetid", "ID Pasangan", targetID, 32);
  wm.addParameter(&customMyID);
  wm.addParameter(&customTargetID);

  if (!wm.autoConnect("Meowl-Setup")) {
    Serial.println("❌ WiFi Connection Timeout");
    currentState = STATE_SETUP;
  } else {
    pref.begin("meowl", false);
    pref.putString("my_id", customMyID.getValue());
    pref.putString("target_id", customTargetID.getValue());
    pref.end();
  }
}

void initMQTT() {
  mqttClient.setServer(vpsHost, mqttPort);
  mqttClient.setCallback(mqttCallback);

  String clientId = "Meowl-" + String(myID);
  if (mqttClient.connect(clientId.c_str())) {
    String notifyTopic = "meowl/" + String(myID) + "/notify";
    String pingTopic   = "meowl/" + String(myID) + "/ping";
    mqttClient.subscribe(notifyTopic.c_str());
    mqttClient.subscribe(pingTopic.c_str());
    Serial.println("✅ MQTT Subscribed: " + notifyTopic);
  }
}

void mqttCallback(char* topic, byte* payload, unsigned int length) {
  String topicStr = String(topic);
  if (topicStr.endsWith("/notify")) {
    // Extract filename if needed or trigger HTTP download
    String filename = "baru_" + String(time(NULL)) + ".wav";
    downloadAudioFile(filename);
  } else if (topicStr.endsWith("/ping")) {
    previousState = currentState;
    currentState = STATE_PING;
    stateTimer = millis();
  }
}

// ─── HARDWARE INIT ──────────────────────────────────────────────────────────
void initOLED() {
  if (!display.begin(SSD1306_SWITCHCAPVCC, 0x3C)) {
    Serial.println("❌ OLED SSD1306 allocation failed!");
  }
  display.clearDisplay();
  display.display();
}

void initI2SMic() {
  i2s_config_t i2s_config = {
    .mode = (i2s_mode_t)(I2S_MODE_MASTER | I2S_MODE_RX),
    .sample_rate = 16000,
    .bits_per_sample = I2S_BITS_PER_SAMPLE_16BIT,
    .channel_format = I2S_CHANNEL_FMT_ONLY_LEFT,
    .communication_format = I2S_COMM_FORMAT_STAND_I2S,
    .intr_alloc_flags = ESP_INTR_FLAG_LEVEL1,
    .dma_buf_count = 8,
    .dma_buf_len = 64,
    .use_apll = false
  };
  i2s_pin_config_t pin_config = {
    .bck_io_num = I2S_MIC_SCK,
    .ws_io_num = I2S_MIC_WS,
    .data_out_num = I2S_PIN_NO_CHANGE,
    .data_in_num = I2S_MIC_SD
  };
  i2s_driver_install(I2S_MIC_PORT, &i2s_config, 0, NULL);
  i2s_set_pin(I2S_MIC_PORT, &pin_config);
}

void initI2SSpeaker() {
  i2s_config_t i2s_config = {
    .mode = (i2s_mode_t)(I2S_MODE_MASTER | I2S_MODE_TX),
    .sample_rate = 16000,
    .bits_per_sample = I2S_BITS_PER_SAMPLE_16BIT,
    .channel_format = I2S_CHANNEL_FMT_ONLY_LEFT,
    .communication_format = I2S_COMM_FORMAT_STAND_I2S,
    .intr_alloc_flags = ESP_INTR_FLAG_LEVEL1,
    .dma_buf_count = 8,
    .dma_buf_len = 64,
    .use_apll = false
  };
  i2s_pin_config_t pin_config = {
    .bck_io_num = I2S_SPK_BCLK,
    .ws_io_num = I2S_SPK_LRC,
    .data_out_num = I2S_SPK_DIN,
    .data_in_num = I2S_PIN_NO_CHANGE
  };
  i2s_driver_install(I2S_SPK_PORT, &i2s_config, 0, NULL);
  i2s_set_pin(I2S_SPK_PORT, &pin_config);
}

void initSDCard() {
  if (!SD.begin(SD_CS_PIN)) {
    Serial.println("❌ SD Card Mount Failed!");
  } else {
    Serial.println("✅ SD Card Mounted");
  }
}

// ─── OLED DISPLAY RENDERER ─────────────────────────────────────────────────
void renderOLED() {
  display.clearDisplay();
  display.setTextColor(SSD1306_WHITE);

  switch (currentState) {
    case STATE_BOOT:
      display.setTextSize(2);
      display.setCursor(32, 24);
      display.println("MEOWL");
      break;

    case STATE_IDLE:
      // Blinking Cat Eyes
      static bool isBlinking = false;
      if (millis() - blinkTimer > 4000) {
        isBlinking = true;
        if (millis() - blinkTimer > 4200) {
          blinkTimer = millis();
          isBlinking = false;
        }
      }
      if (isBlinking) {
        display.fillRoundRect(28, 30, 22, 4, 2, SSD1306_WHITE);
        display.fillRoundRect(78, 30, 22, 4, 2, SSD1306_WHITE);
      } else {
        display.fillRoundRect(28, 14, 22, 36, 11, SSD1306_WHITE);
        display.fillRoundRect(78, 14, 22, 36, 11, SSD1306_WHITE);
      }
      break;

    case STATE_RECORDING:
      display.fillCircle(20, 32, 8, SSD1306_WHITE);
      display.setTextSize(1);
      display.setCursor(36, 28);
      display.println("RECORDING...");
      break;

    case STATE_UPLOADING:
      display.setTextSize(1);
      display.setCursor(24, 20);
      display.println("UPLOADING...");
      display.drawRect(20, 38, 88, 8, SSD1306_WHITE);
      display.fillRect(22, 40, 50, 4, SSD1306_WHITE);
      break;

    case STATE_NOTIFY: {
      int unread = getUnreadMessageCount();
      display.setTextSize(2);
      display.setCursor(54, 15);
      display.write(0x03); // Heart / Envelope symbol
      display.setTextSize(1);
      display.setCursor(20, 42);
      display.printf("%d Pesan Baru", unread);
      break;
    }

    case STATE_PLAYING:
      display.setTextSize(1);
      display.setCursor(36, 10);
      display.println("PLAYING");
      // Animated 5 bars visualizer
      for (int i = 0; i < 5; i++) {
        int h = random(8, 32);
        display.fillRect(30 + i * 14, 55 - h, 8, h, SSD1306_WHITE);
      }
      break;

    case STATE_PAUSED:
      display.fillRect(52, 18, 8, 28, SSD1306_WHITE);
      display.fillRect(68, 18, 8, 28, SSD1306_WHITE);
      display.setTextSize(1);
      display.setCursor(44, 50);
      display.println("PAUSED");
      break;

    case STATE_PING:
      display.setTextSize(2);
      display.setCursor(56, 24);
      display.print("<3"); // Heart symbol
      break;

    case STATE_SETUP:
      display.setTextSize(1);
      display.setCursor(10, 20);
      display.println("Connect to WiFi:");
      display.setCursor(10, 35);
      display.println("Meowl-Setup");
      break;
  }

  display.display();
}
