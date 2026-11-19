import base64,io,json,os,unittest,urllib.error
from unittest.mock import patch
import publish_ciphertext as p
class Response(io.BytesIO):
 def __enter__(self):return self
 def __exit__(self,*_):self.close()
class PublisherTests(unittest.TestCase):
 def run_case(self,exists=False):
  env={'KOKOK_PHONE_CIPHERTEXT':'AA==','KOKOK_FEED_SIGNING_KEY_B64':'synthetic','GH_TOKEN':'synthetic'}
  envelope={'v':1,'kid':'1','enc':'AA==','sig':'AA=='};body=json.dumps(envelope,separators=(',',':'))+'\n';calls=[];reads=0
  def request(r):
   nonlocal reads
   data=json.loads(r.data) if r.data else None;calls.append((r.full_url,r.get_method(),data))
   if '/contents/' in r.full_url and r.get_method()=='GET':
    reads+=1
    if reads==1 and not exists:raise urllib.error.HTTPError(r.full_url,404,'synthetic',{},None)
    answer={'sha':'old','content':base64.b64encode(body.encode()).decode(),'download_url':'https://example.test/feed.json','html_url':'https://example.test/file'}
   elif '/git/ref/heads/' in r.full_url:raise urllib.error.HTTPError(r.full_url,404,'synthetic',{},None)
   else:answer={'sha':'synthetic'}
   return Response(json.dumps(answer).encode())
  with patch.dict(os.environ,env),patch.object(p,'sign_ciphertext',return_value=envelope),patch('pathlib.Path.read_text',return_value='{}'),patch('urllib.request.urlopen',side_effect=request),patch('sys.stdout',new_callable=io.StringIO):p.main()
  return calls
 def test_orphan_creation(self):
  calls=self.run_case();commits=[d for u,m,d in calls if u.endswith('/git/commits')];self.assertEqual(commits[0]['parents'],[])
  tree=[d for u,m,d in calls if u.endswith('/git/trees')][0];self.assertEqual([e['path'] for e in tree['tree']],['feed.json'])
 def test_compare_swap_update(self):
  calls=self.run_case(True);updates=[d for u,m,d in calls if '/contents/' in u and m=='PUT'];self.assertEqual(updates[0]['sha'],'old');self.assertEqual(updates[0]['branch'],'alarm-feed')
if __name__=='__main__':unittest.main()
