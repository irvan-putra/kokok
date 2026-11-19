"""Verify repository-secret custody signs synthetic ciphertext only.
Never prints key material, ciphertext, signature or alarm data.
"""
import base64,json
from pathlib import Path
from tink import hybrid,signature
import tink
from feed_write import app_key_template
from sign_ciphertext import sign_ciphertext
hybrid.register();signature.register()
key=tink.new_keyset_handle(app_key_template())
payload=b'{"v":1,"rev":1,"updated":"synthetic","tz":"UTC","alarms":[]}'
enc=base64.b64encode(key.public_keyset_handle().primitive(hybrid.HybridEncrypt).encrypt(payload,b'kokok-feed-v1')).decode()
import os
pub=json.loads(Path('tools/publisher-public.json').read_text())
e=sign_ciphertext(enc,os.environ['KOKOK_FEED_SIGNING_KEY_B64'],pub)
plain=key.primitive(hybrid.HybridDecrypt).decrypt(base64.b64decode(e['enc']),b'kokok-feed-v1')
assert plain==payload
print('Synthetic ciphertext signature verified against provisioned public key; no personal data used.')
