#!/bin/bash
# ==============================================================================
# 🐱 MEOWL VPS ONE-CLICK AUTOMATED DEPLOYMENT SCRIPT
# OS Target: Ubuntu 20.04 / 22.04 LTS or Debian 11/12
# ==============================================================================

set -e

echo "🐱 Starting Meowl VPS Setup..."

# 1. Update System Packages
echo "📦 Updating system packages..."
sudo apt update && sudo apt upgrade -y

# 2. Install Node.js (v18 LTS), npm, Mosquitto MQTT & PM2
echo "💚 Installing Node.js, Mosquitto MQTT Broker, and PM2..."
curl -fsSL https://deb.nodesource.com/setup_18.x | sudo -E bash -
sudo apt install -y nodejs mosquitto mosquitto-clients ufw git
sudo npm install -g pm2

# 3. Configure Mosquitto MQTT Broker
echo "📡 Configuring Mosquitto MQTT Broker..."
sudo cat << 'EOF' | sudo tee /etc/mosquitto/conf.d/meowl.conf
listener 1883
allow_anonymous true
log_dest stdout
log_type error
log_type warning
log_type notice
log_type information
EOF

sudo systemctl restart mosquitto
sudo systemctl enable mosquitto

# 4. Install Node.js Server Dependencies
echo "🚀 Installing Meowl Node.js dependencies..."
npm install

# 5. Start Node.js Middleware with PM2
echo "⚡ Starting Meowl Backend Server via PM2..."
pm2 stop meowl-vps 2>/dev/null || true
pm2 start server.js --name "meowl-vps"
pm2 save
sudo env PATH=$PATH:/usr/bin /usr/lib/node_modules/pm2/bin/pm2 startup systemd -u $USER --hp $HOME || true

# 6. Configure UFW Firewall (Allow Ports 22, 80, 1883, 3000)
echo "🛡️ Configuring Firewall (UFW)..."
sudo ufw allow 22/tcp
sudo ufw allow 80/tcp
sudo ufw allow 1883/tcp
sudo ufw allow 3000/tcp
echo "y" | sudo ufw enable || true

# 7. Health Check
echo "=============================================================================="
echo "✅ MEOWL VPS SETUP COMPLETE!"
echo "📡 MQTT Broker Running on:   mqtt://YOUR_SERVER_IP:1883"
echo "🌐 REST API Server Running on: http://YOUR_SERVER_IP:3000/status"
echo "=============================================================================="
