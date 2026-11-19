"""Synthetic-only writer checks. No operational keys, endpoints or personal data."""
import argparse
import base64
import json
import pathlib
import tempfile
import unittest

import tink
from tink import cleartext_keyset_handle, hybrid, signature
import feed_write as w


class WriterTest(unittest.TestCase):
    def setUp(self):
        hybrid.register()
        signature.register()
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = pathlib.Path(self.temp.name)
        # Fresh synthetic ephemeral test material, destroyed with temp directory.
        self.sign = tink.new_keyset_handle(signature.signature_key_templates.ECDSA_P256)
        self.app = tink.new_keyset_handle(w.app_key_template())
        for name, handle in [('sign-private', self.sign), ('sign-public', self.sign.public_keyset_handle()),
                             ('app-private', self.app), ('app-public', self.app.public_keyset_handle())]:
            w.write_keyset(handle, self.root / (name + '.json'))

    def test_encrypt_carries_top_level_kid_and_roundtrips(self):
        plain = b'{"v":1,"rev":7,"alarms":[]}'
        (self.root / 'in.json').write_bytes(plain)
        w.cmd_encrypt(argparse.Namespace(input=self.root/'in.json', to=self.root/'app-public.json',
            sign=self.root/'sign-private.json', sign_public=self.root/'sign-public.json', out=self.root/'out.json'))
        env = json.loads((self.root/'out.json').read_text())
        expected = str(json.loads((self.root/'sign-public.json').read_text())['primaryKeyId'])
        self.assertEqual(expected, env['kid'])
        self.assertNotEqual('', env['kid'])
        self.sign.public_keyset_handle().primitive(signature.PublicKeyVerify).verify(
            base64.b64decode(env['sig']), env['enc'].encode())
        self.assertEqual(plain, self.app.primitive(hybrid.HybridDecrypt).decrypt(
            base64.b64decode(env['enc']), w.CONTEXT_INFO))

    def test_pair_accepts_public_config(self):
        w.cmd_pair(argparse.Namespace(url='https://example.org/alarms.json', verify=self.root/'sign-public.json', out=self.root/'pair.json'))
        config = json.loads((self.root/'pair.json').read_text())
        self.assertEqual('https://example.org/alarms.json', config['feedUrl'])
        self.assertTrue(all(k['keyData']['keyMaterialType'] == 'ASYMMETRIC_PUBLIC' for k in config['verifyKeyset']['key']))

    def test_pair_refuses_private_wrong_primitive_and_unsafe_url(self):
        for key in ['sign-private', 'app-public']:
            with self.assertRaises((ValueError, tink.TinkError)):
                w.cmd_pair(argparse.Namespace(url='https://example.org/f', verify=self.root/(key+'.json'), out=self.root/'pair.json'))
        for url in ['http://example.org/f', 'https:///bad', 'https://u:p@example.org/f', 'https://example.org/f#fragment']:
            with self.assertRaises(ValueError):
                w.cmd_pair(argparse.Namespace(url=url, verify=self.root/'sign-public.json', out=self.root/'pair.json'))


if __name__ == '__main__':
    unittest.main()
