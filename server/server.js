/**
 * 🐱 Meowl VPS Middleware Server
 * Node.js + Express.js REST API for Audio File Uploads, Downloads & Real-time Ping Signals
 * Auto-cleans files from server after successful download to prevent ghost re-downloads.
 */

const express = require('express');
const multer  = require('multer');
const fs      = require('fs');
const path    = require('path');
const cors    = require('cors');

const app  = express();
const PORT = process.env.PORT || 3000;
const UPLOAD_BASE_DIR = path.join(__dirname, 'uploads');

// Memory store for pending ping events: pingQueue[target_id] = timestamp
const pingQueue = {};

// Ensure base upload directory exists
if (!fs.existsSync(UPLOAD_BASE_DIR)) {
  fs.mkdirSync(UPLOAD_BASE_DIR, { recursive: true });
}

app.use(cors());
app.use(express.json());

// Configure Multer Storage for file uploads per target_id
const storage = multer.diskStorage({
  destination: (req, file, cb) => {
    const targetId = req.params.target_id || 'default';
    const targetDir = path.join(UPLOAD_BASE_DIR, targetId);
    if (!fs.existsSync(targetDir)) {
      fs.mkdirSync(targetDir, { recursive: true });
    }
    cb(null, targetDir);
  },
  filename: (req, file, cb) => {
    const timestamp = Math.floor(Date.now() / 1000);
    const filename = `baru_${timestamp}.wav`;
    cb(null, filename);
  }
});

const upload = multer({
  storage: storage,
  limits: { fileSize: 5 * 1024 * 1024 } // Max 5MB per WAV audio file
});

const REGISTERED_IDS_FILE = path.join(__dirname, 'registered_ids.json');

function loadRegisteredIds() {
  try {
    if (fs.existsSync(REGISTERED_IDS_FILE)) {
      return JSON.parse(fs.readFileSync(REGISTERED_IDS_FILE, 'utf8'));
    }
  } catch (e) {
    console.error('Error reading registered_ids.json:', e.message);
  }
  return {};
}

function saveRegisteredIds(data) {
  try {
    fs.writeFileSync(REGISTERED_IDS_FILE, JSON.stringify(data, null, 2), 'utf8');
  } catch (e) {
    console.error('Error writing registered_ids.json:', e.message);
  }
}

// ─── ROUTES ─────────────────────────────────────────────────────────────────

// Health Check
app.get('/status', (req, res) => {
  res.json({
    status: 'ONLINE',
    system: 'Meowl VPS Middleware',
    timestamp: new Date().toISOString(),
    uptime: process.uptime()
  });
});

// HTTP POST Register/Claim ID (Prevents ID Duplication by other devices)
app.post('/register-id', (req, res) => {
  const { id, deviceId } = req.body || {};

  if (!id || !deviceId) {
    return res.status(400).json({ success: false, error: 'ID dan Device ID wajib diisi' });
  }

  const cleanId = id.trim();
  const cleanDeviceId = deviceId.trim();
  const registry = loadRegisteredIds();

  if (registry[cleanId]) {
    const existing = registry[cleanId];
    if (existing.deviceId === cleanDeviceId) {
      console.log(`[POST /register-id] Re-registered ID "${cleanId}" for same device: ${cleanDeviceId}`);
      return res.status(200).json({ success: true, message: 'ID milik Anda' });
    } else {
      console.log(`[POST /register-id] REJECTED duplicate claim for ID "${cleanId}" by device: ${cleanDeviceId}`);
      return res.status(409).json({ success: false, error: `ID "${cleanId}" sudah digunakan oleh orang lain!` });
    }
  }

  registry[cleanId] = {
    deviceId: cleanDeviceId,
    registeredAt: new Date().toISOString()
  };
  saveRegisteredIds(registry);

  console.log(`[POST /register-id] Successfully registered new ID "${cleanId}" for device: ${cleanDeviceId}`);
  res.status(200).json({ success: true, message: 'ID berhasil didaftarkan' });
});

// HTTP GET Check ID Availability
app.get('/check-id/:id', (req, res) => {
  const cleanId = req.params.id.trim();
  const registry = loadRegisteredIds();
  const isTaken = !!registry[cleanId];
  res.status(200).json({ id: cleanId, registered: isTaken });
});

// HTTP POST Send Ping Signal to target_id
app.post('/ping/:target_id', (req, res) => {
  const targetId = req.params.target_id;
  const timestamp = Math.floor(Date.now() / 1000);
  pingQueue[targetId] = timestamp;
  console.log(`[POST /ping] Heart Ping sent to target: ${targetId} at ${timestamp}`);
  res.status(200).json({ success: true, target_id: targetId, timestamp: timestamp });
});

// HTTP GET Check & Consume Pending Ping Signal for my_id
app.get('/ping/:my_id', (req, res) => {
  const myId = req.params.my_id;
  if (pingQueue[myId]) {
    const timestamp = pingQueue[myId];
    delete pingQueue[myId]; // Consume ping once retrieved
    console.log(`[GET /ping] Heart Ping delivered to ${myId}`);
    return res.status(200).json({ ping: true, timestamp: timestamp });
  }
  res.status(200).json({ ping: false });
});

// HTTP POST Upload Audio file for target_id
app.post('/upload/:target_id', (req, res) => {
  const contentType = req.headers['content-type'] || '';
  const targetId = req.params.target_id;

  if (contentType.includes('multipart/form-data')) {
    upload.single('audio')(req, res, (err) => {
      if (err) return res.status(500).json({ error: err.message });
      console.log(`[POST /upload] File saved for target ${targetId}: ${req.file.filename}`);
      res.status(201).json({ success: true, file: req.file.filename });
    });
  } else {
    // Handle Raw Stream Upload
    const targetDir = path.join(UPLOAD_BASE_DIR, targetId);
    if (!fs.existsSync(targetDir)) fs.mkdirSync(targetDir, { recursive: true });

    const filename = `baru_${Math.floor(Date.now()/1000)}.wav`;
    const filePath = path.join(targetDir, filename);

    const writeStream = fs.createWriteStream(filePath);
    req.pipe(writeStream);

    writeStream.on('finish', () => {
      console.log(`[POST /upload raw] File saved for ${targetId}: ${filename}`);
      res.status(201).json({ success: true, file: filename });
    });

    writeStream.on('error', (err) => {
      res.status(500).json({ error: err.message });
    });
  }
});

// HTTP GET Download Audio file for my_id (Auto-deletes from server upon completion)
app.get('/download/:my_id/:filename', (req, res) => {
  const { my_id, filename } = req.params;
  const filePath = path.join(UPLOAD_BASE_DIR, my_id, filename);

  if (!fs.existsSync(filePath)) {
    console.log(`[GET /download] File not found: ${filePath}`);
    return res.status(404).json({ error: 'File audio tidak ditemukan' });
  }

  console.log(`[GET /download] Streaming file to ${my_id}: ${filename}`);
  res.setHeader('Content-Type', 'audio/wav');
  const readStream = fs.createReadStream(filePath);
  readStream.pipe(res);

  res.on('finish', () => {
    if (fs.existsSync(filePath)) {
      fs.unlink(filePath, (err) => {
        if (!err) console.log(`[GET /download] File auto-cleaned from server after download: ${filename}`);
      });
    }
  });
});

// HTTP DELETE File from server for my_id
app.delete('/files/:my_id/:filename', (req, res) => {
  const { my_id, filename } = req.params;
  const filePath = path.join(UPLOAD_BASE_DIR, my_id, filename);

  if (fs.existsSync(filePath)) {
    fs.unlinkSync(filePath);
    console.log(`[DELETE /files] File permanently deleted from server for ${my_id}: ${filename}`);
    return res.status(200).json({ success: true });
  }
  res.status(200).json({ success: true, message: 'File already absent' });
});

// List Files for my_id
app.get('/files/:my_id', (req, res) => {
  const my_id = req.params.my_id;
  const targetDir = path.join(UPLOAD_BASE_DIR, my_id);

  if (!fs.existsSync(targetDir)) {
    return res.json({ files: [] });
  }

  const files = fs.readdirSync(targetDir);
  res.json({ files: files });
});

// Start Server
app.listen(PORT, () => {
  console.log(`🚀 Meowl VPS Middleware Server running on port ${PORT}`);
  console.log(`📂 Upload directory: ${UPLOAD_BASE_DIR}`);
});
