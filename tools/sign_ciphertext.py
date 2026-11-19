#!/usr/bin/env python3
"""Sign already encrypted Kokok payloads. Never accepts alarm plaintext.
Private key arrives only through the process environment, not argv or disk.
"""
import base64,json,os,sys
import tink
from tink import signature,cleartext_keyset_handle

def sign_ciphertext(enc,key_b64,pub):
    if not isinstance(enc,str) or not enc or len(enc)>500000:
        raise ValueError('invalid ciphertext length')
    raw=base64.b64decode(enc,validate=True)
    if not raw or base64.b64encode(raw).decode()!=enc:
        raise ValueError('ciphertext must be canonical base64')
    if not pub.get('key') or any(k.get('keyData',{}).get('keyMaterialType')!='ASYMMETRIC_PUBLIC' for k in pub['key']):
        raise ValueError('verification key must be public only')
    signature.register()
    priv=cleartext_keyset_handle.read(tink.JsonKeysetReader(base64.b64decode(key_b64,validate=True).decode()))
    verify=cleartext_keyset_handle.read(tink.JsonKeysetReader(json.dumps(pub)))
    sig=priv.primitive(signature.PublicKeySign).sign(enc.encode())
    verify.primitive(signature.PublicKeyVerify).verify(sig,enc.encode())
    return {'v':1,'kid':str(pub['primaryKeyId']),'enc':enc,'sig':base64.b64encode(sig).decode()}

def main():
    try:
        data=json.load(sys.stdin)
        result=sign_ciphertext(data['enc'],os.environ['KOKOK_FEED_SIGNING_KEY_B64'],data['verifyKeyset'])
        json.dump(result,sys.stdout,separators=(',',':'))
        sys.stdout.write('\n')
    except Exception:
        # Error details can contain decoded key or event data. Keep them out of logs.
        sys.stderr.write('ciphertext signing failed; no envelope produced\n')
        sys.exit(1)
if __name__=='__main__':main()
