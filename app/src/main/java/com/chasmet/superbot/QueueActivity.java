package com.chasmet.superbot;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class QueueActivity extends Activity {
    private LinearLayout list;
    private final SimpleDateFormat format = new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRANCE);

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_queue);
        list = findViewById(R.id.queueList);
        findViewById(R.id.buttonBack).setOnClickListener(v -> finish());
        refresh();
    }

    @Override protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        if (list == null) return;
        list.removeAllViews();
        List<PublicationTask> tasks = PublicationTaskRepository.load(this);
        tasks.sort((a,b) -> Long.compare(a.scheduledAt, b.scheduledAt));
        if (tasks.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("Aucune publication programmée");
            empty.setTextColor(0xFF9FB0D0);
            empty.setPadding(8, 24, 8, 24);
            list.addView(empty);
            return;
        }
        for (PublicationTask task : tasks) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(16, 14, 16, 14);
            card.setBackgroundColor(0xFF141D33);
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2);
            cp.setMargins(0, 0, 0, 12);
            card.setLayoutParams(cp);

            TextView title = new TextView(this);
            title.setText(task.platform + " • " + format.format(new Date(task.scheduledAt)));
            title.setTextColor(0xFFFFFFFF);
            title.setTextSize(16);
            card.addView(title);

            TextView state = new TextView(this);
            state.setText(task.status + (task.title == null || task.title.isEmpty() ? "" : "\n" + task.title));
            state.setTextColor(0xFF9FB0D0);
            state.setPadding(0, 6, 0, 6);
            card.addView(state);

            Button delete = new Button(this);
            delete.setText("Supprimer");
            delete.setOnClickListener(v -> {
                PublicationTaskRepository.delete(this, task.id);
                refresh();
            });
            card.addView(delete);
            list.addView(card);
        }
    }
}
