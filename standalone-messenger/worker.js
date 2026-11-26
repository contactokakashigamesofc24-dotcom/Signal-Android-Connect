const json = (body, status = 200) => new Response(JSON.stringify(body), {status, headers: {"Content-Type":"application/json; charset=utf-8", "Cache-Control":"no-store", "Access-Control-Allow-Origin":"*", "Access-Control-Allow-Methods":"GET,POST,OPTIONS", "Access-Control-Allow-Headers":"Authorization,Content-Type"}});
const emailNorm = (s) => String(s || "").trim().toLowerCase();
const now = () => new Date().toISOString();
const uuid = () => crypto.randomUUID();
const bytes = (n) => crypto.getRandomValues(new Uint8Array(n));
const b64u = (b) => btoa(String.fromCharCode(...b)).replace(/\+/g,"-").replace(/\//g,"_").replace(/=+$/g,"");
const unb64u = (s) => { const v=String(s).replace(/-/g,"+").replace(/_/g,"/"); return Uint8Array.from(atob(v+"=".repeat((4-v.length%4)%4)),c=>c.charCodeAt(0)); };
let schemaReady;
async function ensureSchema(env) {
  if(!schemaReady) schemaReady=env.DB.exec(`CREATE TABLE IF NOT EXISTS users(id TEXT PRIMARY KEY,email TEXT NOT NULL COLLATE NOCASE UNIQUE,display_name TEXT NOT NULL,password_salt TEXT NOT NULL,password_hash TEXT NOT NULL,created_at TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS sessions(token_hash TEXT PRIMARY KEY,user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,expires_at TEXT NOT NULL,created_at TEXT NOT NULL);
CREATE INDEX IF NOT EXISTS sessions_user_idx ON sessions(user_id);
CREATE TABLE IF NOT EXISTS conversations(id TEXT PRIMARY KEY,created_at TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS conversation_members(conversation_id TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,joined_at TEXT NOT NULL,PRIMARY KEY(conversation_id,user_id));
CREATE INDEX IF NOT EXISTS conversation_members_user_idx ON conversation_members(user_id);
CREATE TABLE IF NOT EXISTS messages(id TEXT PRIMARY KEY,conversation_id TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,sender_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,body TEXT NOT NULL,created_at TEXT NOT NULL);
CREATE INDEX IF NOT EXISTS messages_conversation_time_idx ON messages(conversation_id,created_at,id);`);
  await schemaReady;
}
async function sha256(s) { return new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(s))); }
async function derive(password, salt) {
  const key = await crypto.subtle.importKey("raw", new TextEncoder().encode(password), "PBKDF2", false, ["deriveBits"]);
  return new Uint8Array(await crypto.subtle.deriveBits({name:"PBKDF2", hash:"SHA-256", salt, iterations:100000}, key, 256));
}
function equal(a,b) { if(a.length!==b.length)return false;let x=0;for(let i=0;i<a.length;i++)x|=a[i]^b[i];return x===0; }
async function readBody(request) {
  const len=Number(request.headers.get("Content-Length")||0); if(len>32768) throw new Error("request_too_large");
  return await request.json();
}
async function createSession(env,userId) {
  const token=b64u(bytes(32));const tokenHash=b64u(await sha256(token));const expires=new Date(Date.now()+30*86400000).toISOString();
  await env.DB.prepare("INSERT INTO sessions(token_hash,user_id,expires_at,created_at) VALUES(?,?,?,?)").bind(tokenHash,userId,expires,now()).run();
  return {token,expiresAt:expires};
}
async function auth(request,env) {
  const header=request.headers.get("Authorization")||""; if(!header.startsWith("Bearer "))return null;
  const token=header.slice(7); if(token.length<32||token.length>256)return null;
  const hash=b64u(await sha256(token));
  return await env.DB.prepare("SELECT u.id,u.email,u.display_name FROM sessions s JOIN users u ON u.id=s.user_id WHERE s.token_hash=? AND s.expires_at>? LIMIT 1").bind(hash,now()).first();
}
function validEmail(email){return email.length<=254 && /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)}
export default {
  async fetch(request, env) {
    const url=new URL(request.url);
    if(request.method==="OPTIONS")return new Response(null,{status:204,headers:{"Access-Control-Allow-Origin":"*","Access-Control-Allow-Methods":"GET,POST,OPTIONS","Access-Control-Allow-Headers":"Authorization,Content-Type","Access-Control-Max-Age":"86400"}});
    try {
      if(url.pathname==="/health"&&request.method==="GET")return json({ok:true,service:"standalone-messenger-dev",version:1});
      await ensureSchema(env);
      if(url.pathname==="/api/register"&&request.method==="POST"){
        const b=await readBody(request),email=emailNorm(b.email),password=String(b.password||""),name=String(b.displayName||"").trim().slice(0,80);
        if(!validEmail(email))return json({error:"invalid_email"},400);
        if(password.length<10||password.length>128)return json({error:"password_must_be_10_to_128_chars"},400);
        if(name.length<1)return json({error:"display_name_required"},400);
        const salt=bytes(16),hash=await derive(password,salt),id=uuid();
        try { await env.DB.prepare("INSERT INTO users(id,email,display_name,password_salt,password_hash,created_at) VALUES(?,?,?,?,?,?)").bind(id,email,name,b64u(salt),b64u(hash),now()).run(); }
        catch(e){ if(String(e).includes("UNIQUE"))return json({error:"email_already_registered"},409); throw e; }
        const session=await createSession(env,id);
        return json({user:{id,email,displayName:name},session},201);
      }
      if(url.pathname==="/api/login"&&request.method==="POST"){
        const b=await readBody(request),email=emailNorm(b.email),password=String(b.password||"");
        const user=await env.DB.prepare("SELECT id,email,display_name,password_salt,password_hash FROM users WHERE email=? LIMIT 1").bind(email).first();
        if(!user)return json({error:"invalid_credentials"},401);
        const got=await derive(password,unb64u(user.password_salt));
        const expected=unb64u(user.password_hash);
        if(!equal(got,expected))return json({error:"invalid_credentials"},401);
        const session=await createSession(env,user.id);
        return json({user:{id:user.id,email:user.email,displayName:user.display_name},session});
      }
      const user=await auth(request,env); if(!user)return json({error:"unauthorized"},401);
      if(url.pathname==="/api/me"&&request.method==="GET")return json({user:{id:user.id,email:user.email,displayName:user.display_name}});
      if(url.pathname==="/api/conversations"&&request.method==="GET"){
        const result=await env.DB.prepare(`SELECT c.id,c.created_at,other.id AS peer_id,other.email AS peer_email,other.display_name AS peer_name,(SELECT body FROM messages m WHERE m.conversation_id=c.id ORDER BY m.created_at DESC,m.id DESC LIMIT 1) AS last_message,(SELECT created_at FROM messages m WHERE m.conversation_id=c.id ORDER BY m.created_at DESC,m.id DESC LIMIT 1) AS last_message_at FROM conversations c JOIN conversation_members mine ON mine.conversation_id=c.id AND mine.user_id=? JOIN conversation_members peer ON peer.conversation_id=c.id AND peer.user_id<>? JOIN users other ON other.id=peer.user_id ORDER BY COALESCE(last_message_at,c.created_at) DESC LIMIT 100`).bind(user.id,user.id).all();
        return json({conversations:result.results||[]});
      }
      if(url.pathname==="/api/conversations"&&request.method==="POST"){
        const b=await readBody(request),email=emailNorm(b.email); if(!validEmail(email))return json({error:"invalid_email"},400);
        const peer=await env.DB.prepare("SELECT id,email,display_name FROM users WHERE email=? LIMIT 1").bind(email).first();
        if(!peer)return json({error:"user_not_found"},404); if(peer.id===user.id)return json({error:"cannot_message_self"},400);
        const existing=await env.DB.prepare(`SELECT c.id FROM conversations c JOIN conversation_members a ON a.conversation_id=c.id AND a.user_id=? JOIN conversation_members b ON b.conversation_id=c.id AND b.user_id=? WHERE (SELECT COUNT(*) FROM conversation_members m WHERE m.conversation_id=c.id)=2 LIMIT 1`).bind(user.id,peer.id).first();
        if(existing)return json({conversation:{id:existing.id,peer}});
        const id=uuid(),at=now();
        await env.DB.batch([env.DB.prepare("INSERT INTO conversations(id,created_at) VALUES(?,?)").bind(id,at),env.DB.prepare("INSERT INTO conversation_members(conversation_id,user_id,joined_at) VALUES(?,?,?)").bind(id,user.id,at),env.DB.prepare("INSERT INTO conversation_members(conversation_id,user_id,joined_at) VALUES(?,?,?)").bind(id,peer.id,at)]);
        return json({conversation:{id,peer}},201);
      }
      const match=url.pathname.match(/^\/api\/conversations\/([0-9a-f-]+)\/messages$/i);
      if(match&&request.method==="GET"){
        const cid=match[1],member=await env.DB.prepare("SELECT 1 AS ok FROM conversation_members WHERE conversation_id=? AND user_id=?").bind(cid,user.id).first();
        if(!member)return json({error:"conversation_not_found"},404);
        const limit=Math.min(100,Math.max(1,Number(url.searchParams.get("limit")||50)));
        const result=await env.DB.prepare("SELECT m.id,m.sender_id,u.display_name AS sender_name,m.body,m.created_at FROM messages m JOIN users u ON u.id=m.sender_id WHERE m.conversation_id=? ORDER BY m.created_at DESC,m.id DESC LIMIT ?").bind(cid,limit).all();
        return json({messages:(result.results||[]).reverse()});
      }
      if(match&&request.method==="POST"){
        const cid=match[1],b=await readBody(request),body=String(b.body||"").trim();
        if(!body||body.length>5000)return json({error:"message_must_be_1_to_5000_chars"},400);
        const member=await env.DB.prepare("SELECT 1 AS ok FROM conversation_members WHERE conversation_id=? AND user_id=?").bind(cid,user.id).first();
        if(!member)return json({error:"conversation_not_found"},404);
        const id=uuid(),at=now();
        await env.DB.prepare("INSERT INTO messages(id,conversation_id,sender_id,body,created_at) VALUES(?,?,?,?,?)").bind(id,cid,user.id,body,at).run();
        return json({message:{id,conversationId:cid,senderId:user.id,body,createdAt:at}},201);
      }
      if(url.pathname==="/api/logout"&&request.method==="POST"){
        const raw=request.headers.get("Authorization").slice(7),hash=b64u(await sha256(raw));
        await env.DB.prepare("DELETE FROM sessions WHERE token_hash=?").bind(hash).run();return json({ok:true});
      }
      return json({error:"not_found"},404);
    } catch(e) { return json({error:"server_error",detail:String(e?.message||e)},500); }
  }
};
