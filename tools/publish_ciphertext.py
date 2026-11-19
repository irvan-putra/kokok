#!/usr/bin/env python3
"""Sign phone ciphertext and publish only its envelope to the dedicated feed branch.
No plaintext alarm payload, private key or phone public key is an input or output.
"""
import base64,json,os,sys,urllib.request,urllib.error
from pathlib import Path
from sign_ciphertext import sign_ciphertext
API='https://api.github.com/repos/irvan-putra/kokok'
BRANCH='alarm-feed'
FILE='feed.json'

def main():
    # Values passed in env, never interpolated as shell code or printed.
    enc=os.environ['KOKOK_PHONE_CIPHERTEXT']
    pub=json.loads(Path('tools/publisher-public.json').read_text())
    envelope=sign_ciphertext(enc,os.environ['KOKOK_FEED_SIGNING_KEY_B64'],pub)
    data=json.dumps(envelope,separators=(',',':'))+'\n'
    headers={'Authorization':'Bearer '+os.environ['GH_TOKEN'],'User-Agent':'kokok-feed-publisher','Accept':'application/vnd.github+json'}
    def api(path,body=None,method=None):
        req=urllib.request.Request(API+path,headers=headers,data=json.dumps(body).encode() if body is not None else None,method=method)
        with urllib.request.urlopen(req) as r:return json.load(r)
    # Never create a source-derived branch accidentally containing personal data.
    try:
        current=api('/contents/'+FILE+'?ref='+BRANCH)
    except urllib.error.HTTPError as e:
        if e.code!=404:raise
        # Distinguish absent branch from missing target path. Fail closed if a
        # branch already exists without the expected envelope file.
        try:api('/git/ref/heads/'+BRANCH)
        except urllib.error.HTTPError as x:
            if x.code!=404:raise
        else:raise ValueError('unexpected existing feed branch')
        tree=api('/git/trees',{'tree':[{'path':FILE,'mode':'100644','type':'blob','content':data}]},'POST')
        commit=api('/git/commits',{'message':'Publish encrypted alarm feed','tree':tree['sha'],'parents':[]},'POST')
        api('/git/refs',{'ref':'refs/heads/'+BRANCH,'sha':commit['sha']},'POST')
    else:
        # Contents API updates are compare-and-swap; concurrent revisions must
        # fail rather than overwrite an unexpected publisher update.
        api('/contents/'+FILE,{'message':'Update encrypted alarm feed','branch':BRANCH,'sha':current['sha'],'content':base64.b64encode(data.encode()).decode()},'PUT')
    latest=api('/contents/'+FILE+'?ref='+BRANCH)
    if base64.b64decode(latest['content']).decode()!=data:raise ValueError('published readback differs')
    print('Encrypted envelope published and API readback verified. CDN propagation and phone application remain unverified.')
    print('Feed URL:',latest['download_url'])
    print('Commit file:',latest['html_url'])

if __name__=='__main__':
    try:main()
    except Exception:
        sys.stderr.write('publisher failed; inspect metadata separately, no input or secret details logged\n');sys.exit(1)
