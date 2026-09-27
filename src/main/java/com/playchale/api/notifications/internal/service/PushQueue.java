package com.playchale.api.notifications.internal.service;

/** Where deliveries to phones run: off the request, so a slow push service never holds anyone up. */
interface PushQueue {

	void submit(Runnable delivery);

}
