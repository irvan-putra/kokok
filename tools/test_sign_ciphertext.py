import unittest,base64,io,json,os,subprocess,sys
import tink
from tink import signature,cleartext_keyset_handle
from sign_ciphertext import sign_ciphertext
signature.register()
def serial(k):
 s=io.StringIO();cleartext_keyset_handle.write(tink.JsonKeysetWriter(s),k);return s.getvalue()
class SignerTests(unittest.TestCase):
 def setUp(self):
  self.key=tink.new_keyset_handle(signature.signature_key_templates.ECDSA_P256)
  self.b64=base64.b64encode(serial(self.key).encode()).decode();self.pub=json.loads(serial(self.key.public_keyset_handle()))
 def test_roundtrip(self):
  enc=base64.b64encode(b'synthetic opaque ciphertext').decode();e=sign_ciphertext(enc,self.b64,self.pub)
  self.key.public_keyset_handle().primitive(signature.PublicKeyVerify).verify(base64.b64decode(e['sig']),enc.encode())
  self.assertEqual(e['kid'],str(self.pub['primaryKeyId']));self.assertEqual(set(e),{'v','kid','enc','sig'})
 def test_bad_inputs(self):
  for enc in ['', 'not base64', 'AA==\n']:
   with self.assertRaises(Exception):sign_ciphertext(enc,self.b64,self.pub)
  other=tink.new_keyset_handle(signature.signature_key_templates.ECDSA_P256)
  with self.assertRaises(Exception):sign_ciphertext('AA==',self.b64,json.loads(serial(other.public_keyset_handle())))
  with self.assertRaises(Exception):sign_ciphertext('AA==',self.b64,json.loads(serial(self.key)))
 def test_failure_redacts(self):
  marker='SYNTHETIC_INVALID_KEY_MUST_NOT_PRINT'
  r=subprocess.run([sys.executable,'sign_ciphertext.py'],input=json.dumps({'enc':'AA==','verifyKeyset':self.pub}),text=True,capture_output=True,env={**os.environ,'KOKOK_FEED_SIGNING_KEY_B64':marker})
  self.assertNotEqual(r.returncode,0);self.assertEqual(r.stdout,'');self.assertNotIn(marker,r.stderr)
if __name__ == "__main__":unittest.main()
