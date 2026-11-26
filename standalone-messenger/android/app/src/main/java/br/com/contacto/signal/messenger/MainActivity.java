package br.com.contacto.signal.messenger;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.inputmethod.InputMethodManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final String API = "https://signal-clone-messenger-dev.contacto-kakashigamesofc24.workers.dev";
    private static final int NAVY = Color.rgb(19, 29, 50), CARD = Color.rgb(33, 47, 74), WHITE = Color.rgb(245,248,255), MUTED = Color.rgb(171,185,208), BLUE = Color.rgb(173,204,255), DARK = Color.rgb(20,31,52);
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private String token="", userId="", email="", displayName="", currentConversation="", peerName="", peerEmail="";
    private LinearLayout root, list;
    private EditText messageBox;
    private boolean registerMode=false;
    private final Runnable poll = new Runnable(){public void run(){if(!token.isEmpty() && currentConversation.isEmpty()) loadConversations(false); else if(!token.isEmpty()) loadMessages(false); handler.postDelayed(this,5000);}};

    @Override public void onCreate(Bundle state){super.onCreate(state);getWindow().setStatusBarColor(NAVY);getWindow().setNavigationBarColor(NAVY);prefs=getSharedPreferences("messenger",MODE_PRIVATE);token=prefs.getString("token","");userId=prefs.getString("id","");email=prefs.getString("email","");displayName=prefs.getString("name","");if(token.isEmpty())showAuth();else showHome();}
    private int dp(float n){return (int)(n*getResources().getDisplayMetrics().density+.5f);}
    private LinearLayout column(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);l.setPadding(dp(20),dp(18),dp(20),dp(16));l.setBackgroundColor(NAVY);return l;}
    private TextView text(String s,int size,int color,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;}
    private void setRoot(View v){setContentView(v);}
    private void showAuth(){currentConversation="";root=column();ScrollView sc=new ScrollView(this);LinearLayout body=column();body.setPadding(dp(8),dp(42),dp(8),dp(20));TextView mark=text("SI",34,BLUE,true);mark.setGravity(Gravity.CENTER);mark.setBackground(round(CARD,dp(28)));LinearLayout.LayoutParams mp=new LinearLayout.LayoutParams(dp(76),dp(76));mp.gravity=Gravity.CENTER_HORIZONTAL;body.addView(mark,mp);TextView title=text("Meu Mensageiro",25,WHITE,true);title.setGravity(Gravity.CENTER);title.setPadding(0,dp(18),0,dp(8));body.addView(title);TextView subtitle=text(registerMode?"Crie uma conta do mensageiro":"Entre com seu e-mail e senha",15,MUTED,false);subtitle.setGravity(Gravity.CENTER);subtitle.setPadding(0,0,0,dp(26));body.addView(subtitle);
        final EditText name=registerMode?input("Seu nome",false):null;if(name!=null)body.addView(name,lp(-1,dp(54),0,0,0,dp(12)));
        EditText em=input("E-mail",false);em.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);body.addView(em,lp(-1,dp(54),0,0,0,dp(12)));
        EditText pass=input("Senha (mínimo 10 caracteres)",true);body.addView(pass,lp(-1,dp(54),0,0,0,dp(18)));
        Button go=button(registerMode?"Criar conta":"Entrar");body.addView(go,lp(-1,dp(52),0,0,0,dp(14)));
        TextView toggle=text(registerMode?"Já tenho conta — entrar":"Criar uma conta nova",15,BLUE,true);toggle.setGravity(Gravity.CENTER);toggle.setPadding(0,dp(15),0,dp(15));body.addView(toggle);
        TextView notice=text("VERSÃO DE TESTE — não use mensagens privadas. Ainda sem criptografia ponta a ponta; o cadastro também não confirma o e-mail.",13,Color.rgb(255,211,135),false);notice.setGravity(Gravity.CENTER);notice.setPadding(dp(6),dp(24),dp(6),0);body.addView(notice);
        go.setOnClickListener(v->{String e=em.getText().toString().trim(),p=pass.getText().toString();String n=name==null?"":name.getText().toString().trim();if(e.isEmpty()||p.isEmpty()||(registerMode&&n.isEmpty())){toast("Preencha todos os campos.");return;}boolean creating=registerMode;String path=creating?"/api/register":"/api/login";JSONObject b=new JSONObject();try{b.put("email",e);b.put("password",p);if(creating)b.put("displayName",n);}catch(Exception ignored){}busy(go,true);post(path,b,"",(data,error)->{busy(go,false);if(error!=null){toast(error);return;}try{JSONObject u=data.getJSONObject("user"),s=data.getJSONObject("session");token=s.getString("token");userId=u.getString("id");email=u.getString("email");displayName=u.getString("displayName");prefs.edit().putString("token",token).putString("id",userId).putString("email",email).putString("name",displayName).apply();showHome();}catch(Exception ex){toast("Resposta do servidor inválida.");}});});
        toggle.setOnClickListener(v->{registerMode=!registerMode;showAuth();});sc.addView(body);setRoot(sc);}
    private android.graphics.drawable.GradientDrawable round(int color,int radius){android.graphics.drawable.GradientDrawable d=new android.graphics.drawable.GradientDrawable();d.setColor(color);d.setCornerRadius(radius);return d;}
    private EditText input(String hint,boolean password){EditText e=new EditText(this);e.setSingleLine(true);e.setTextColor(WHITE);e.setHintTextColor(MUTED);e.setHint(hint);e.setTextSize(16);e.setPadding(dp(15),0,dp(15),0);e.setBackground(round(CARD,dp(14)));if(password)e.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);return e;}
    private Button button(String s){Button b=new Button(this);b.setText(s);b.setTextColor(DARK);b.setTextSize(15);b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);b.setAllCaps(false);b.setBackground(round(BLUE,dp(14)));return b;}
    private LinearLayout.LayoutParams lp(int w,int h,int l,int t,int r,int b){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(w<0?w:dp(w),h<0?h:dp(h));p.setMargins(dp(l),dp(t),dp(r),dp(b));return p;}
    private void showHome(){currentConversation="";root=column();LinearLayout bar=new LinearLayout(this);bar.setGravity(Gravity.CENTER_VERTICAL);TextView title=text("Conversas",23,WHITE,true);bar.addView(title,new LinearLayout.LayoutParams(0,dp(52),1));TextView logout=text("Sair",14,BLUE,true);bar.addView(logout);logout.setOnClickListener(v->request("POST","/api/logout",new JSONObject(),token,(d,e)->{prefs.edit().clear().apply();token="";showAuth();}));root.addView(bar);
        TextView who=text(displayName+" · "+email,13,MUTED,false);who.setPadding(0,0,0,dp(15));root.addView(who);
        Button add=button("Nova conversa por e-mail");root.addView(add,lp(-1,dp(50),0,0,0,dp(10)));add.setOnClickListener(v->{EditText e=input("E-mail da pessoa cadastrada",false);new AlertDialog.Builder(this).setTitle("Nova conversa").setView(e).setNegativeButton("Cancelar",null).setPositiveButton("Abrir",(d,w)->createConversation(e.getText().toString().trim())).show();});
        ScrollView sc=new ScrollView(this);list=new LinearLayout(this);list.setOrientation(LinearLayout.VERTICAL);sc.addView(list);root.addView(sc,new LinearLayout.LayoutParams(-1,0,1));setRoot(root);loadConversations(true);}
    private void loadConversations(boolean showError){request("GET","/api/conversations",null,token,(d,e)->{if(e!=null){if(showError)toast(e);return;}try{JSONArray a=d.getJSONArray("conversations");list.removeAllViews();if(a.length()==0){TextView empty=text("Nenhuma conversa ainda. Toque em “Nova conversa por e-mail” para começar.",15,MUTED,false);empty.setPadding(dp(12),dp(22),dp(12),dp(22));list.addView(empty);return;}for(int i=0;i<a.length();i++){JSONObject c=a.getJSONObject(i);String cid=c.getString("id"),pn=c.optString("peer_name","Contato"),pe=c.optString("peer_email","");String last=c.optString("last_message","Toque para conversar");LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);card.setPadding(dp(15),dp(13),dp(15),dp(13));card.setBackground(round(CARD,dp(15)));TextView n=text(pn,17,WHITE,true);TextView m=text(last,14,MUTED,false);card.addView(n);m.setPadding(0,dp(5),0,0);card.addView(m);LinearLayout.LayoutParams cp=lp(-1,dp(72),0,0,0,dp(9));list.addView(card,cp);card.setOnClickListener(v->{peerName=pn;peerEmail=pe;openConversation(cid);});}}catch(Exception ex){if(showError)toast("Não consegui ler as conversas.");}});}
    private void createConversation(String e){JSONObject b=new JSONObject();try{b.put("email",e);}catch(Exception ignored){}request("POST","/api/conversations",b,token,(d,error)->{if(error!=null){toast(error);return;}try{JSONObject c=d.getJSONObject("conversation"),p=c.optJSONObject("peer");peerName=p==null?e:p.optString("display_name",e);peerEmail=p==null?e:p.optString("email",e);openConversation(c.getString("id"));}catch(Exception ex){toast("Não consegui abrir a conversa.");}});}
    private void openConversation(String id){currentConversation=id;root=column();LinearLayout bar=new LinearLayout(this);bar.setGravity(Gravity.CENTER_VERTICAL);TextView back=text("‹",34,BLUE,true);bar.addView(back,lp(42,52,0,0,8,0));back.setOnClickListener(v->showHome());LinearLayout names=new LinearLayout(this);names.setOrientation(LinearLayout.VERTICAL);names.addView(text(peerName,18,WHITE,true));names.addView(text(peerEmail,12,MUTED,false));bar.addView(names,new LinearLayout.LayoutParams(0,dp(52),1));root.addView(bar);ScrollView sc=new ScrollView(this);list=new LinearLayout(this);list.setOrientation(LinearLayout.VERTICAL);list.setPadding(0,dp(10),0,dp(10));sc.addView(list);root.addView(sc,new LinearLayout.LayoutParams(-1,0,1));LinearLayout compose=new LinearLayout(this);compose.setGravity(Gravity.CENTER_VERTICAL);messageBox=input("Mensagem",false);compose.addView(messageBox,new LinearLayout.LayoutParams(0,dp(52),1));Button send=button("Enviar");compose.addView(send,lp(84,dp(50),dp(8),0,0,0));root.addView(compose,lp(-1,dp(60),0,dp(8),0,0));send.setOnClickListener(v->sendMessage());setRoot(root);loadMessages(true);}
    private void loadMessages(boolean showError){String cid=currentConversation;if(cid.isEmpty())return;request("GET","/api/conversations/"+cid+"/messages?limit=60",null,token,(d,e)->{if(e!=null){if(showError)toast(e);return;}if(!cid.equals(currentConversation))return;try{JSONArray a=d.getJSONArray("messages");list.removeAllViews();for(int i=0;i<a.length();i++){JSONObject m=a.getJSONObject(i);boolean mine=userId.equals(m.optString("sender_id"));LinearLayout row=new LinearLayout(this);row.setGravity(mine?Gravity.RIGHT:Gravity.LEFT);TextView bubble=text((mine?"":""+m.optString("sender_name","")+"\n")+m.optString("body",""),15,mine?DARK:WHITE,false);bubble.setPadding(dp(13),dp(10),dp(13),dp(10));bubble.setBackground(round(mine?BLUE:CARD,dp(16)));LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(Math.min(getResources().getDisplayMetrics().widthPixels-dp(75),-2),-2);bp.setMargins(dp(2),dp(4),dp(2),dp(4));row.addView(bubble,bp);list.addView(row);}list.post(()->{View child=list.getChildAt(list.getChildCount()-1);if(child!=null)child.requestFocus();});}catch(Exception ex){if(showError)toast("Não consegui carregar mensagens.");}});}
    private void sendMessage(){String body=messageBox.getText().toString().trim(),cid=currentConversation;if(body.isEmpty())return;messageBox.setText("");JSONObject b=new JSONObject();try{b.put("body",body);}catch(Exception ignored){}request("POST","/api/conversations/"+cid+"/messages",b,token,(d,e)->{if(e!=null){messageBox.setText(body);toast(e);}else loadMessages(true);});}
    private interface Callback{void done(JSONObject data,String error);}
    private void post(String path,JSONObject body,String auth,Callback cb){request("POST",path,body,auth,cb);}
    private void request(String method,String path,JSONObject body,String auth,Callback cb){io.execute(()->{HttpURLConnection c=null;try{c=(HttpURLConnection)new URL(API+path).openConnection();c.setRequestMethod(method);c.setConnectTimeout(12000);c.setReadTimeout(18000);c.setRequestProperty("Accept","application/json");if(auth!=null&&!auth.isEmpty())c.setRequestProperty("Authorization","Bearer "+auth);if(body!=null){c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json; charset=utf-8");byte[] b=body.toString().getBytes(StandardCharsets.UTF_8);try(OutputStream o=c.getOutputStream()){o.write(b);}}int code=c.getResponseCode();InputStream in=(code>=200&&code<300)?c.getInputStream():c.getErrorStream();StringBuilder s=new StringBuilder();if(in!=null)try(BufferedReader r=new BufferedReader(new InputStreamReader(in,StandardCharsets.UTF_8))){String line;while((line=r.readLine())!=null)s.append(line);}JSONObject data=s.length()==0?new JSONObject():new JSONObject(s.toString());if(code<200||code>=300){String error=data.optString("error","HTTP "+code);if("invalid_credentials".equals(error))error="E-mail ou senha incorretos.";if("email_already_registered".equals(error))error="Este e-mail já tem conta. Entre em vez de criar outra.";if("user_not_found".equals(error))error="Não achei uma conta com esse e-mail.";throw new Exception(error);}JSONObject result=data;handler.post(()->cb.done(result,null));}catch(Exception e){String m=e.getMessage()==null?"Falha de conexão.":e.getMessage();handler.post(()->cb.done(null,m));}finally{if(c!=null)c.disconnect();}});}
    private void busy(Button b,boolean on){if(on){b.setTag(b.getText().toString());b.setText("Aguarde…");}else{Object old=b.getTag();if(old!=null)b.setText(old.toString());}b.setEnabled(!on);b.setAlpha(on?0.7f:1f);}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
    @Override protected void onResume(){super.onResume();handler.removeCallbacks(poll);handler.postDelayed(poll,5000);}
    @Override protected void onPause(){handler.removeCallbacks(poll);super.onPause();}
    @Override protected void onDestroy(){io.shutdownNow();super.onDestroy();}
}
