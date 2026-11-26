package br.com.contacto.signal;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class LuizWelcomePrototypeActivity extends Activity {
    private static final int BG = Color.rgb(28, 27, 34);
    private static final int TEXT = Color.rgb(245, 244, 248);
    private static final int SECONDARY = Color.rgb(202, 201, 208);
    private LinearLayout root;
    private int screen = 0;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(0);
        showWelcome();
    }

    private int dp(float value) { return (int)(value * getResources().getDisplayMetrics().density + .5f); }

    private TextView label(String value, float size, int color) {
        TextView t = new TextView(this);
        t.setText(value); t.setTextSize(size); t.setTextColor(color);
        t.setGravity(Gravity.CENTER); t.setIncludeFontPadding(true);
        return t;
    }

    private Button action(String caption, boolean primary, Runnable onClick) {
        Button b = new Button(this);
        b.setText(caption); b.setAllCaps(false); b.setTextSize(16); b.setTextColor(TEXT);
        b.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        b.setMinHeight(dp(60));
        b.setBackground(round(primary ? Color.rgb(62, 69, 91) : Color.rgb(37, 39, 47), 36));
        b.setOnClickListener(v -> onClick.run());
        return b;
    }

    private android.graphics.drawable.GradientDrawable round(int color, float radius) {
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setColor(color); d.setCornerRadius(dp(radius)); return d;
    }

    private LinearLayout page() {
        LinearLayout p = new LinearLayout(this); p.setOrientation(LinearLayout.VERTICAL);
        p.setBackgroundColor(BG); p.setPadding(dp(22), dp(8), dp(22), dp(22));
        return p;
    }

    private void showWelcome() {
        screen = 0;
        root = page(); root.setGravity(Gravity.CENTER_HORIZONTAL);
        Illustration illustration = new Illustration(this);
        root.addView(illustration, new LinearLayout.LayoutParams(-1, 0, 1.42f));

        TextView headline = label("Leve a privacidade com\nvocê.\nSeja você em cada\nmensagem.", 25, TEXT);
        headline.setLineSpacing(dp(1), 1f);
        LinearLayout.LayoutParams head = new LinearLayout.LayoutParams(-1, -2);
        head.topMargin = dp(10); head.bottomMargin = dp(42); root.addView(headline, head);

        TextView about = label("O Signal é uma organização sem fins\nlucrativos", 15, SECONDARY);
        root.addView(about, new LinearLayout.LayoutParams(-1, -2));
        TextView terms = label("Termos e Política de Privacidade", 15, SECONDARY);
        terms.setOnClickListener(v -> showLegalInfo());
        LinearLayout.LayoutParams legal = new LinearLayout.LayoutParams(-1, -2);
        legal.topMargin = dp(4); legal.bottomMargin = dp(30); root.addView(terms, legal);

        Button continueButton = action("Continuar", true, this::showPhoneSetup);
        root.addView(continueButton, new LinearLayout.LayoutParams(-1, dp(62)));
        Button restoreButton = action("Restaurar ou transferir", false, this::showRestore);
        LinearLayout.LayoutParams restore = new LinearLayout.LayoutParams(-1, dp(62));
        restore.topMargin = dp(24); root.addView(restoreButton, restore);
        setContentView(root);
    }

    private void showLegalInfo() {
        new AlertDialog.Builder(this).setTitle("Termos e Política de Privacidade")
                .setMessage("Os termos e a política de privacidade deste novo aplicativo serão apresentados aqui antes do cadastro.")
                .setPositiveButton("Fechar", null).show();
    }

    private void showPhoneSetup() {
        screen = 1; root = page();
        TextView back = label("‹", 38, TEXT); back.setGravity(Gravity.CENTER_VERTICAL | Gravity.LEFT);
        back.setOnClickListener(v -> showWelcome()); root.addView(back, new LinearLayout.LayoutParams(-1, dp(50)));
        TextView title = label("Seu número de telefone", 25, TEXT); title.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, dp(72)); tp.topMargin = dp(32); root.addView(title, tp);
        TextView detail = label("Digite o número que você quer usar neste mensageiro.", 16, SECONDARY);
        detail.setGravity(Gravity.LEFT | Gravity.TOP); LinearLayout.LayoutParams detailP = new LinearLayout.LayoutParams(-1, -2); detailP.topMargin = dp(8); root.addView(detail, detailP);
        EditText number = new EditText(this); number.setSingleLine(true); number.setInputType(InputType.TYPE_CLASS_PHONE); number.setHint("+55  (DDD)  número"); number.setHintTextColor(Color.GRAY); number.setTextColor(TEXT); number.setPadding(dp(14), 0, dp(14), 0); number.setBackground(round(Color.rgb(38,39,46), 14));
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(-1, dp(58)); np.topMargin = dp(28); root.addView(number, np);
        View spacer = new View(this); root.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1));
        Button next = action("Continuar", true, () -> {
            String digits = number.getText().toString().replaceAll("\\D", "");
            if (digits.length() < 10) { number.setError("Digite um número com DDD."); return; }
            new AlertDialog.Builder(this).setTitle("Número informado")
                    .setMessage("A próxima etapa será a confirmação do número. O envio e a verificação por SMS ainda precisam ser conectados ao serviço do mensageiro.")
                    .setPositiveButton("Entendi", null).show();
        }); root.addView(next, new LinearLayout.LayoutParams(-1, dp(62)));
        setContentView(root);
    }

    private void showRestore() {
        screen = 2; root = page();
        TextView back = label("‹", 38, TEXT); back.setGravity(Gravity.CENTER_VERTICAL | Gravity.LEFT);
        back.setOnClickListener(v -> showWelcome()); root.addView(back, new LinearLayout.LayoutParams(-1, dp(50)));
        TextView title = label("Restaurar ou transferir", 25, TEXT); title.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, dp(72)); tp.topMargin = dp(28); root.addView(title, tp);
        TextView detail = label("Traga suas conversas de um arquivo de backup ou de outro aparelho.", 16, SECONDARY);
        detail.setGravity(Gravity.LEFT | Gravity.TOP); LinearLayout.LayoutParams dp1 = new LinearLayout.LayoutParams(-1, -2); dp1.topMargin = dp(8); root.addView(detail, dp1);
        View spacer = new View(this); root.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1));
        Button restoreFile = action("Escolher arquivo de backup", true, () -> {
            Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT); pick.setType("*/*"); pick.addCategory(Intent.CATEGORY_OPENABLE);
            try { startActivityForResult(pick, 100); } catch (Exception e) { new AlertDialog.Builder(this).setMessage("Não foi possível abrir os arquivos.").setPositiveButton("Fechar", null).show(); }
        }); root.addView(restoreFile, new LinearLayout.LayoutParams(-1, dp(62)));
        Button transfer = action("Transferir de outro aparelho", false, () -> new AlertDialog.Builder(this)
                .setTitle("Transferência")
                .setMessage("A transferência entre aparelhos será conectada em uma próxima etapa do mensageiro.")
                .setPositiveButton("Fechar", null).show());
        LinearLayout.LayoutParams tr = new LinearLayout.LayoutParams(-1, dp(62)); tr.topMargin = dp(18); root.addView(transfer, tr);
        setContentView(root);
    }

    @Override public void onBackPressed() {
        if (screen != 0) showWelcome(); else super.onBackPressed();
    }

    private static class Illustration extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Illustration(Activity c) { super(c); setLayerType(View.LAYER_TYPE_SOFTWARE, null); }
        private void fill(Canvas c, int color) { p.setColor(color); p.setStyle(Paint.Style.FILL); }
        private void stroke(Canvas c, int color, float width) { p.setColor(color); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(width); p.setStrokeCap(Paint.Cap.ROUND); p.setStrokeJoin(Paint.Join.ROUND); }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float s = Math.min(getWidth()/360f, getHeight()/330f); if (s <= 0) return;
            canvas.save(); canvas.translate((getWidth()-360*s)/2f, (getHeight()-330*s)/2f); canvas.scale(s,s);
            int blue = Color.rgb(44,96,143), deep = Color.rgb(26,57,94), red = Color.rgb(210,57,20), cream = Color.rgb(229,184,134), pink=Color.rgb(202,33,81), pale=Color.rgb(196,225,233), dark=Color.rgb(25,27,37);
            // Large phone with privacy-themed abstract conversation artwork.
            p.setShadowLayer(8,0,5,Color.argb(70,0,0,0)); fill(canvas, Color.WHITE); canvas.drawRoundRect(new RectF(52,5,226,274),24,24,p); p.clearShadowLayer();
            fill(canvas, deep); canvas.drawRoundRect(new RectF(57,10,221,269),20,20,p);
            Path field = new Path(); field.moveTo(60,18);field.lineTo(218,18);field.lineTo(218,242);field.lineTo(60,266);field.close();fill(canvas,blue);canvas.drawPath(field,p);
            Path lower = new Path();lower.moveTo(60,176);lower.lineTo(126,218);lower.lineTo(218,235);lower.lineTo(218,266);lower.lineTo(60,266);lower.close();fill(canvas,Color.rgb(44,112,154));canvas.drawPath(lower,p);
            // Person in the phone.
            fill(canvas,cream);canvas.drawCircle(175,39,14,p);fill(canvas,dark);canvas.drawOval(new RectF(161,21,188,36),p);
            Path body = new Path();body.moveTo(160,54);body.lineTo(195,57);body.lineTo(205,122);body.lineTo(166,133);body.close();fill(canvas,red);canvas.drawPath(body,p);
            fill(canvas,pale);Path shirt=new Path();shirt.moveTo(177,55);shirt.lineTo(187,57);shirt.lineTo(179,117);shirt.lineTo(169,120);shirt.close();canvas.drawPath(shirt,p);
            stroke(canvas,cream,8);canvas.drawLine(168,73,145,138,p);canvas.drawLine(197,76,208,126,p);
            fill(canvas,cream);canvas.drawOval(new RectF(202,121,216,132),p);
            fill(canvas,dark);canvas.drawRoundRect(new RectF(169,127,204,136),5,5,p);
            // Person outside the phone, reaching toward it.
            fill(canvas,cream);canvas.drawCircle(255,133,12,p);fill(canvas,dark);canvas.drawOval(new RectF(244,118,263,129),p);
            Path coat=new Path();coat.moveTo(243,147);coat.lineTo(271,147);coat.lineTo(284,219);coat.lineTo(232,219);coat.close();fill(canvas,pink);canvas.drawPath(coat,p);
            Path shirt2=new Path();shirt2.moveTo(252,147);shirt2.lineTo(261,148);shirt2.lineTo(253,192);shirt2.lineTo(243,194);shirt2.close();fill(canvas,Color.WHITE);canvas.drawPath(shirt2,p);
            stroke(canvas,cream,7);canvas.drawLine(245,158,221,183,p);canvas.drawLine(270,158,290,202,p);fill(canvas,cream);canvas.drawCircle(218,186,4,p);canvas.drawCircle(292,205,4,p);
            fill(canvas,cream);canvas.drawRoundRect(new RectF(239,213,251,260),5,5,p);canvas.drawRoundRect(new RectF(265,213,278,260),5,5,p);
            stroke(canvas,Color.WHITE,8);canvas.drawLine(245,260,232,267,p);canvas.drawLine(272,260,286,267,p);
            // Small privacy badge on the upper-right side.
            fill(canvas,Color.rgb(15,16,21));canvas.drawRoundRect(new RectF(307,34,355,80),18,18,p);fill(canvas,Color.WHITE);canvas.drawCircle(331,57,16,p);fill(canvas,Color.rgb(248,173,55));canvas.drawCircle(331,57,11,p);p.setTextAlign(Paint.Align.CENTER);p.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);p.setTextSize(13);p.setColor(Color.WHITE);canvas.drawText("✓",331,62,p);
            canvas.restore();
        }
    }
}

