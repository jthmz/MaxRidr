package com.maxapacity.ride;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;

/** PayPal handles the amount and checkout. The home phone can also display the owner's QR. */
final class Support {
    static final String DONATE = "https://www.paypal.com/donate/?cmd=_donations&business=jaytee5611%40gmail.com"
            + "&item_name=Support+MaxRidr&currency_code=USD";

    static void donate(Host host) {
        host.launch(new Intent(Intent.ACTION_VIEW, Uri.parse(DONATE)));
    }

    static View card(Context c, Host host) {
        LinearLayout card = Ui.card(c);
        card.addView(Ui.title(c, "Support MaxRidr"));
        View donate = Ui.button(c, "Donate with PayPal", Icons.EXTERNAL_LINK, Ui.PRIMARY);
        donate.setOnClickListener(v -> donate(host));
        card.addView(donate, Ui.top(c, Ui.matchWrap(), 12));
        View scan = Ui.button(c, "Show PayPal QR", Icons.LINK, Ui.SECONDARY);
        scan.setOnClickListener(v -> {
            ImageView qr = new ImageView(c);
            qr.setImageResource(R.drawable.paypal_support);
            qr.setAdjustViewBounds(true);
            qr.setMaxHeight(Ui.dp(c, 280));
            qr.setScaleType(ImageView.ScaleType.FIT_CENTER);
            qr.setContentDescription("PayPal payment QR for MAXAPACITY");
            new Sheet(host.frame(), "Scan with your phone", null).view(qr)
                    .action("Done", Ui.SECONDARY, true, null).show();
        });
        card.addView(scan, Ui.top(c, Ui.matchWrap(), 8));
        return card;
    }
}
