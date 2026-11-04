#!/bin/sh
set -u

wifi_ip="$(ipconfig getifaddr en0 2>/dev/null || true)"
local_name="$(scutil --get LocalHostName 2>/dev/null || true)"
health_path="/api/v1/health"
runtime_tsconfig="/Users/eric/Library/Application Support/RealLanguage/runtime/tsconfig.base.json"
launch_agent_plist="/Users/eric/Library/LaunchAgents/com.reallanguage.local-api.plist"
failed=0

check_http() {
  label="$1"
  url="$2"
  body="$(curl --noproxy '*' --silent --show-error --max-time 5 "$url" 2>/dev/null || true)"
  case "$body" in
    *'"status":"ok"'*'"service":"api"'*)
      printf '%s=OK %s\n' "$label" "$url"
      ;;
    *)
      printf '%s=FAILED %s\n' "$label" "$url"
      failed=1
      ;;
  esac
}

printf 'WIFI_IPV4=%s\n' "${wifi_ip:-MISSING}"
printf 'LOCAL_HOSTNAME=%s\n' "${local_name:-MISSING}"

if launchctl list 2>/dev/null | grep -q 'com.reallanguage.local-api'; then
  printf 'LAUNCH_AGENT=OK\n'
elif [ -f "$launch_agent_plist" ] && plutil -lint "$launch_agent_plist" >/dev/null 2>&1; then
  printf 'LAUNCH_AGENT=FAILED VALID_PLIST_NOT_LOADED\n'
  failed=1
else
  printf 'LAUNCH_AGENT=FAILED MISSING_OR_INVALID_PLIST\n'
  failed=1
fi

if [ -f "$runtime_tsconfig" ]; then
  printf 'RUNTIME_TSCONFIG_BASE=OK\n'
else
  printf 'RUNTIME_TSCONFIG_BASE=FAILED MISSING_RUNTIME_ROOT_CONFIG\n'
  failed=1
fi

listener="$(lsof -nP -iTCP:3000 -sTCP:LISTEN 2>/dev/null || true)"
if printf '%s' "$listener" | grep -q '\*:3000'; then
  printf 'PORT_3000=OK LISTEN_ALL_INTERFACES\n'
else
  printf 'PORT_3000=FAILED_OR_LOOPBACK_ONLY\n'
  failed=1
fi

check_http LOOPBACK_HEALTH "http://127.0.0.1:3000$health_path"

if [ -n "$wifi_ip" ]; then
  lan_url="http://$wifi_ip:3000$health_path"
  check_http LAN_HEALTH "$lan_url"
  printf 'LAN_HEALTH_URL=%s\n' "$lan_url"
else
  printf 'LAN_HEALTH=FAILED NO_WIFI_IPV4\n'
  failed=1
fi

if [ -n "$local_name" ]; then
  check_http MDNS_HEALTH "http://$local_name.local:3000$health_path"
else
  printf 'MDNS_HEALTH=FAILED NO_LOCAL_HOSTNAME\n'
fi

firewall_state="$(/usr/libexec/ApplicationFirewall/socketfilterfw --getglobalstate 2>/dev/null || true)"
printf 'MAC_FIREWALL=%s\n' "${firewall_state:-UNKNOWN}"

build_config="apps/android/app/build/generated/source/buildConfig/debug/com/reallanguage/app/BuildConfig.java"
properties_file="apps/android/gradle.properties"
saved_discovery=""
if [ -f "$properties_file" ]; then
  saved_discovery="$(sed -n 's/^REAL_LANGUAGE_API_NSD_SERVICE_TYPE=//p' "$properties_file" | head -1)"
fi
if [ -n "$wifi_ip" ] && [ -f "$properties_file" ]; then
  if [ "$saved_discovery" = "_reallanguage._tcp." ]; then
    printf 'ANDROID_SAVED_DISCOVERY=OK %s\n' "$saved_discovery"
  elif grep -Fqx "REAL_LANGUAGE_API_FALLBACK_BASE_URL=http://$wifi_ip:3000" "$properties_file"; then
    printf 'ANDROID_SAVED_FALLBACK=OK\n'
  else
    printf 'ANDROID_SAVED_FALLBACK=FAILED STALE_OR_MISSING_WIFI_FALLBACK\n'
    failed=1
  fi
fi
if [ -f "$build_config" ]; then
  printf 'ANDROID_BUILD_ENDPOINTS:\n'
  build_endpoints="$(grep -E 'API_.*BASE_URL|API_NSD_SERVICE_TYPE' "$build_config" || true)"
  printf '%s\n' "$build_endpoints"
  if printf '%s' "$build_endpoints" | grep -q 'API_BASE_URL = "https://api.invalid"'; then
    printf 'ANDROID_BUILD_ENDPOINTS_RESULT=FAILED PLACEHOLDER_PRIMARY\n'
    failed=1
  elif ! printf '%s' "$build_endpoints" | grep -q 'API_BASE_URL = "http://'; then
    printf 'ANDROID_BUILD_ENDPOINTS_RESULT=FAILED NO_LOCAL_PRIMARY\n'
    failed=1
  elif printf '%s' "$build_endpoints" | grep -q 'API_NSD_SERVICE_TYPE = "_reallanguage._tcp\."'; then
    printf 'ANDROID_BUILD_DISCOVERY=OK _reallanguage._tcp.\n'
    printf 'ANDROID_BUILD_ENDPOINTS_RESULT=OK\n'
  elif [ -n "$wifi_ip" ] && ! printf '%s' "$build_endpoints" | grep -q "API_FALLBACK_BASE_URL = \"http://$wifi_ip:3000\""; then
    printf 'ANDROID_BUILD_ENDPOINTS_RESULT=FAILED STALE_OR_MISSING_WIFI_FALLBACK\n'
    failed=1
  else
    printf 'ANDROID_BUILD_ENDPOINTS_RESULT=OK\n'
  fi
else
  printf 'ANDROID_BUILD_ENDPOINTS=NOT_BUILT\n'
  failed=1
fi

if pgrep -f '/usr/bin/dns-sd -R RealLanguage _reallanguage._tcp local 3000' >/dev/null 2>&1; then
  printf 'DNS_SD_ADVERTISEMENT=OK _reallanguage._tcp.\n'
else
  printf 'DNS_SD_ADVERTISEMENT=FAILED NOT_RUNNING\n'
  failed=1
fi

if [ "$failed" -eq 0 ]; then
  printf 'MAC_SIDE_RESULT=PASS\n'
  printf 'NEXT=Open LAN_HEALTH_URL on the physical phone. If it fails there, inspect phone permission/VPN/proxy and router client isolation.\n'
else
  printf 'MAC_SIDE_RESULT=FAIL\n'
fi

exit "$failed"
