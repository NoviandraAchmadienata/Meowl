# 🐱 Meowl VPS Setup & Deployment Guide

Panduan lengkap instalasi dan konfigurasi server **VPS (Virtual Private Server)** untuk sistem **Meowl IoT Voicemail & Android App**.

---

## 📋 Persyaratan Server (System Requirements)
- **OS VPS:** Ubuntu 20.04 / 22.04 LTS atau Debian 11/12.
- **Spesifikasi Minimal:** 1 vCPU, 512 MB RAM, 10 GB Disk Storage (misalnya paket VPS terjangkau DigitalOcean $4/bulan, AWS EC2 t3.micro/t4g.nano, Vultr, Linode, Biznet, dll).
- **Akses:** Akses SSH `root` atau `sudo`.

---

## ⚡ Cara Instalasi Otomatis (1-Click Automated Setup)

1. **Masuk ke VPS Anda via SSH Terminal:**
   ```bash
   ssh root@IP_VPS_ANDA
   ```

2. **Clone / Upload Folder Server ke VPS:**
   ```bash
   git clone https://github.com/USERNAME/Meowl.git
   cd Meowl/server
   ```

3. **Jalankan Script Deploy Otomatis:**
   ```bash
   chmod +x deploy.sh
   ./deploy.sh
   ```

Script di atas akan secara otomatis menginstall Node.js v18, MQTT Mosquitto Broker, PM2, membuka port Firewall, dan menjalankan server $24/7$.

---

## 🛠️ Cara Instalasi Manual (Step-by-Step)

Jika Anda ingin melakukan instalasi langkah demi langkah:

### 1. Update Paket System
```bash
sudo apt update && sudo apt upgrade -y
```

### 2. Install Node.js (v18 LTS) & PM2
```bash
curl -fsSL https://deb.nodesource.com/setup_18.x | sudo -E bash -
sudo apt install -y nodejs git
sudo npm install -g pm2
```

### 3. Install & Konfigurasi MQTT Mosquitto Broker
```bash
sudo apt install -y mosquitto mosquitto-clients

# Buat berkas konfigurasi meowl
sudo cat << 'EOF' | sudo tee /etc/mosquitto/conf.d/meowl.conf
listener 1883
allow_anonymous true
log_dest stdout
log_type error
log_type warning
EOF

# Restart service Mosquitto
sudo systemctl restart mosquitto
sudo systemctl enable mosquitto
```

### 4. Install Dependencies & Jalankan Backend Node.js
```bash
cd /path/to/Meowl/server
npm install

# Jalankan server $24/7$ dengan PM2
pm2 start server.js --name "meowl-vps"
pm2 save
pm2 startup
```

### 5. Buka Port Firewall (UFW)
```bash
sudo ufw allow 22/tcp    # SSH
sudo ufw allow 1883/tcp  # MQTT Mosquitto Broker
sudo ufw allow 3000/tcp  # REST API Server
sudo ufw enable
```

---

## 🔍 Cara Uji Coba Server (Verification)

1. **Cek Status Server Node.js (HTTP GET):**
   Buka browser atau terminal HP/Komputer Anda:
   ```bash
   curl http://IP_VPS_ANDA:3000/status
   ```
   **Respon Sukses:**
   ```json
   {
     "status": "ONLINE",
     "system": "Meowl VPS Middleware",
     "timestamp": "2026-08-09T10:22:00.000Z",
     "uptime": 124.5
   }
   ```

2. **Cek Status Service PM2:**
   ```bash
   pm2 status
   ```

3. **Cek Status Log Server:**
   ```bash
   pm2 logs meowl-vps
   ```

---

## 📱 Konfigurasi pada Aplikasi Android

Setelah VPS aktif, buka aplikasi **Meowl** di HP Android Anda:
1. Klik tombol **Pengaturan ⚙️** di pojok kanan atas.
2. Masukkan alamat IP VPS Anda pada kolom **VPS Server Address** (contoh: `http://103.123.45.67:3000` atau alamat IP VPS Anda).
3. Klik **Simpan**. Aplikasi & Widget Anda kini terhubung secara *real-time* ke VPS!
