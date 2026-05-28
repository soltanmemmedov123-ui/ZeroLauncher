pkg update && pkg upgrade
pkg install nodejs
cp /path/to/index.js ~/mcbot/
mkdir ~/mcbot
cp /path/to/index.js ~/mcbot/
cp /path/to/downlands/index.js ~/mcbot/
termux-setup-storage
npm install mineflayer mineflayer-pathfinder discord.js
cd ~/storage/downloads
exit
node index.js
cd ~/ejjdj
rm -rf *
cp /sdcard/Download/ZeroLauncher-v13.zip .
unzip -q ZeroLauncher-v13.zip
cp -r ZalithLauncherTOWOReborn-1.4.4.5-TOWO/.github .
rsync -a ZalithLauncherTOWOReborn-1.4.4.5-TOWO/ . 2>/dev/null || cp -r ZalithLauncherTOWOReborn-1.4.4.5-TOWO/. .
rm -rf ZalithLauncherTOWOReborn-1.4.4.5-TOWO
rm ZeroLauncher-v13.zip
git add .
git commit -m "ZeroLauncher v13"
git push
cd ~/ejjddj
sed -i 's/private.*uniqueUUID/public String uniqueUUID/' ZalithLauncher/src/main/java/net/kdt/pojavlaunch/MinecraftAccount.java
# After find shows you the path, open it directly
nano $(find . -name "MinecraftAccount.java" -type f)
cp ~
cd ~
termux-setup-storage && sleep 3
sed -i 's|^\(deb.*\)://[^ ]*/termux-main|\1://packages.termux.dev/apt/termux-main|' $PREFIX/etc/apt/sources.list
pkg update -y && pkg install -y curl
curl -sL https://raw.githubusercontent.com/jarvesusaram99/open-claude-code-termux/main/termux_setup.sh -o ~/termux_setup.sh
curl -sL https://raw.githubusercontent.com/jarvesusaram99/open-claude-code-termux/main/scripts/mobile_tools.sh -o ~/scripts/mobile_tools.sh --create-dirs
curl -sL https://raw.githubusercontent.com/jarvesusaram99/open-claude-code-termux/main/scripts/setup_shizuku.sh -o ~/setup_shizuku.sh
chmod +x ~/scripts/mobile_tools.sh ~/setup_shizuku.sh
bash ~/setup_shizuku.sh
echo "export NODE_OPTIONS=--dns-result-order=ipv4first" >> ~/.bashrc
export NODE_OPTIONS=--dns-result-order=ipv4first
bash ~/termux_setup.sh
claude --limitless
bash ~/termux_setup.sh
claude --limitless
pkg update -y
pkg install -y openjdk-17 aapt apktool
pkg install openjdk-21 -y
pkg install git -y
pkg install wget -y
mkdir mc_checker
cd mc_checker
nano MinecraftAccountChecker.java
nano ComboScraper.java
javac ComboScraper.java
java ComboScraper
nano ComboGenerator.java
javac ComboGenerator.java
java ComboGenerator live 10000
java ComboGenerator live 50000
java ComboGenerator live 5000000
cd ~
mkdir roblox && cd roblox
nano RobloxAccountFinder.java
javac RobloxAccountFinder.java
java RobloxAccountFinder 20000 20
cd ~
cd ZeroLauncher/ZalithLauncherTOWOReborn-1.4.4.5-TOWO
rm -rf *
cp /sdcard/Download/ZeroLauncher-v14-TOWO.zip .
unzip -q ZeroLauncher-v14-TOWO.zip
cp -r ZalithLauncherTOWOReborn-1.4.4.5-TOWO/.github .
rsync -a ZalithLauncherTOWOReborn-1.4.4.5-TOWO/ . 2>/dev/null || cp -r ZalithLauncherTOWOReborn-1.4.4.5-TOWO/. .
rm -rf ZalithLauncherTOWOReborn-1.4.4.5-TOWO
rm ZeroLauncher-v14-TOWO.zip
git add .
git commit -m "ZeroLauncher"
git push
git push --set-upstream origin main
cd ZeroLauncher/ZalithLauncherTOWOReborn-1.4.4.5-TOWO
rm -rf *
cp /sdcard/Download/ZeroLauncher-v14-TOWO-fixed.zip .
unzip -q ZeroLauncher-v14-TOWO-fixed.zip
cp -r ZalithLauncherTOWOReborn-1.4.4.5-TOWO/.github .
rsync -a ZalithLauncherTOWOReborn-1.4.4.5-TOWO/ . 2>/dev/null || cp -r ZalithLauncherTOWOReborn-1.4.4.5-TOWO/. .
rm -rf ZalithLauncherTOWOReborn-1.4.4.5-TOWO
rm ZeroLauncher-v14-TOWO-fixed.zip
git add .
git commit -m "ZeroLauncher"
git push
cd ZeroLauncher/ZalithLauncherTOWOReborn-1.4.4.5-TOWO
rm -rf *
cp /sdcard/Download/ZeroLauncher-v14-TOWO-fixed.zip .
unzip -q ZeroLauncher-v14-TOWO-fixed.zip
cp -r ZalithLauncherTOWOReborn-1.4.4.5-TOWO/.github .
rsync -a ZalithLauncherTOWOReborn-1.4.4.5-TOWO/ . 2>/dev/null || cp -r ZalithLauncherTOWOReborn-1.4.4.5-TOWO/. .
rm -rf ZalithLauncherTOWOReborn-1.4.4.5-TOWO
rm ZeroLauncher-v14-TOWO-fixed.zip
git add .
git commit -m "ZeroLauncher"
git push
cd ~/ejjdj
rm -rf *
cp /sdcard/Download/ZeroLauncher-v14-TOWO.zip .
unzip -q ZeroLauncher-v14-TOWO.zip
cp -r ZalithLauncherTOWOReborn-1.4.4.5-TOWO/.github .
rsync -a ZalithLauncherTOWOReborn-1.4.4.5-TOWO/ . 2>/dev/null || cp -r ZalithLauncherTOWOReborn-1.4.4.5-TOWO/. .
rm -rf ZalithLauncherTOWOReborn-1.4.4.5-TOWO
rm ZeroLauncher-v14-TOWO.zip
git add .
git commit -m "ZeroLauncher"
git push
