#!/usr/bin/env bash
# Launches the Fabric dev client (gradlew runClient) for each Minecraft
# version, one at a time. Each launch blocks until the game window is
# closed; then the next version starts. gradle.properties is restored to
# the 26.2 defaults when the whole sequence is done.
set -e
cd "$(dirname "$0")"

export JAVA_HOME="C:/Users/negi6/jdk-25/jdk-25.0.4+7"

# Each row: minecraft_version fabric_api_version minecraft_requirement [mappings]
launches=(
  "1.21.11 0.141.6+1.21.11 ~1.21.11 mappings"
  "26.1 0.145.1+26.1 ~26.1"
  "26.1.1 0.145.4+26.1.1 ~26.1.1"
  "26.1.2 0.155.2+26.1.2 ~26.1.2"
  "26.2 0.156.0+26.2 ~26.2"
)

for entry in "${launches[@]}"; do
  set -- $entry
  mc=$1
  fab=$2
  req=$3
  maps=""
  if [ -n "$4" ]; then
    maps="mojang_mappings=true"
  fi
  echo "=============================================="
  echo "Launching KitLoader for Minecraft $mc"
  echo "Close the game window when done testing."
  echo "=============================================="
  cat > gradle.properties <<EOF
# Fabric Properties
minecraft_version=$mc
loader_version=0.19.3

# Mod Properties
mod_version=1.0.0-$mc
maven_group=com.example
archives_base_name=kitloader
minecraft_requirement=$req

# Fabric API
fabric_version=$fab
$maps
EOF
  ./gradlew runClient --console=plain
  echo "Minecraft $mc closed."
done

# Restore the 26.2 configuration as the project default
cat > gradle.properties <<EOF
# Fabric Properties
minecraft_version=26.2
loader_version=0.19.3

# Mod Properties
mod_version=1.0.0-26.2
maven_group=com.example
archives_base_name=kitloader
minecraft_requirement=~26.2

# Fabric API
fabric_version=0.156.0+26.2
EOF

echo "=============================================="
echo "All versions launched. gradle.properties restored to 26.2 defaults."
