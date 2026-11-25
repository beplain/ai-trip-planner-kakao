"""Enable compression and cache only content-hashed Expo assets; preserve API/TLS config."""
import datetime
import pathlib
import shutil
import subprocess

config = pathlib.Path('/etc/nginx/sites-enabled/ai-trip-planner').resolve()
if not config.is_relative_to(pathlib.Path('/etc/nginx')):
    raise SystemExit('Unexpected nginx configuration target')
original = config.read_text()
marker = '# ai-trip-planner web performance'
if marker in original:
    print('Performance configuration already installed')
    raise SystemExit()
anchor = '    server_name beplain-trip.duckdns.org;'
if anchor not in original:
    raise SystemExit('Expected HTTPS server not found')
settings = '''
    # ai-trip-planner web performance
    gzip on;
    gzip_vary on;
    gzip_comp_level 5;
    gzip_min_length 1024;
    gzip_types application/javascript text/javascript text/css application/json application/manifest+json image/svg+xml;

    location ^~ /_expo/static/ {
        root /var/www/ai-trip-planner;
        try_files $uri =404;
        add_header Cache-Control "public, max-age=31536000, immutable";
    }
    location = /index.html {
        root /var/www/ai-trip-planner;
        add_header Cache-Control "no-cache";
    }
'''
backup = config.with_name(config.name + '.backup-' + datetime.datetime.now().strftime('%Y%m%d%H%M%S'))
shutil.copy2(config, backup)
config.write_text(original.replace(anchor, anchor + '\n' + settings, 1))
try:
    subprocess.run(['nginx', '-t'], check=True)
    subprocess.run(['systemctl', 'reload', 'nginx'], check=True)
except Exception:
    shutil.copy2(backup, config)
    subprocess.run(['nginx', '-t'], check=False)
    raise
print('Installed compression and hashed-asset caching; backup:', backup)
