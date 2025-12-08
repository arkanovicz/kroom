// store.js - minimal Redux-like state management
// Part of kroom-webapp-assets

/**
 * Create a Redux-like store
 * @param {function} reducer - (state, action) => newState
 * @param {*} initialState - Initial state value
 * @param {function} enhancer - Optional store enhancer (e.g., from applyMiddleware)
 */
function createStore(reducer, initialState, enhancer) {
    if (enhancer) return enhancer(createStore)(reducer, initialState);

    let state = initialState;
    let listeners = [];

    return {
        getState: () => state,
        dispatch: (action) => {
            state = reducer(state, action);
            listeners.forEach(fn => fn());
            return action;
        },
        subscribe: (fn) => {
            listeners.push(fn);
            return () => { listeners = listeners.filter(l => l !== fn); };
        }
    };
}

/**
 * Apply middleware to store
 * @param {...function} middlewares - Middleware functions: store => next => action => result
 */
function applyMiddleware(...middlewares) {
    return createStore => (reducer, initialState) => {
        const store = createStore(reducer, initialState);
        let dispatch = store.dispatch;
        middlewares.slice().reverse().forEach(mw => {
            dispatch = mw(store)(dispatch);
        });
        return { ...store, dispatch };
    };
}

/**
 * Combine multiple reducers into one
 * @param {object} reducers - { key: reducer } map
 */
function combineReducers(reducers) {
    return (state = {}, action) => {
        const newState = {};
        let changed = false;
        for (const key in reducers) {
            const reducer = reducers[key];
            const prevSlice = state[key];
            const newSlice = reducer(prevSlice, action);
            newState[key] = newSlice;
            if (newSlice !== prevSlice) changed = true;
        }
        return changed ? newState : state;
    };
}

// Common middleware

/**
 * Logging middleware (for development)
 */
const logMiddleware = (store) => (next) => (action) => {
    console.log('Action:', action.type, action.payload || '');
    const result = next(action);
    console.log('State:', store.getState());
    return result;
};

/**
 * Thunk middleware - allows dispatching functions
 */
const thunkMiddleware = (store) => (next) => (action) => {
    if (typeof action === 'function') {
        return action(store.dispatch, store.getState);
    }
    return next(action);
};
