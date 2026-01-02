// store.js - Redux-like state management
// Part of kroom-webapp-assets

/**
 * Create a Redux-like store
 * @param {Function} reducer - (state, action) => newState
 * @param {*} initialState - Initial state value
 * @param {Function} enhancer - Optional store enhancer (e.g., applyMiddleware)
 * @returns {Object} Store with getState, dispatch, subscribe methods
 */
function createStore(reducer, initialState, enhancer) {
    if (typeof enhancer === 'function') {
        return enhancer(createStore)(reducer, initialState);
    }

    let state = initialState;
    let listeners = [];

    return {
        getState: () => state,

        dispatch: (action) => {
            if (!action || typeof action.type !== 'string') {
                console.warn('Action must have a type property');
                return action;
            }
            state = reducer(state, action);
            listeners.forEach(fn => fn());
            return action;
        },

        subscribe: (fn) => {
            listeners.push(fn);
            return () => {
                listeners = listeners.filter(l => l !== fn);
            };
        },

        replaceReducer: (nextReducer) => {
            reducer = nextReducer;
        }
    };
}

/**
 * Compose middleware into a store enhancer
 * @param {...Function} middlewares - Middleware functions
 * @returns {Function} Store enhancer
 */
function applyMiddleware(...middlewares) {
    return (createStore) => (reducer, initialState) => {
        const store = createStore(reducer, initialState);
        let dispatch = store.dispatch;

        const middlewareAPI = {
            getState: store.getState,
            dispatch: (action) => dispatch(action)
        };

        const chain = middlewares.map(mw => mw(middlewareAPI));
        dispatch = chain.reduceRight((next, mw) => mw(next), store.dispatch);

        return { ...store, dispatch };
    };
}

/**
 * Combine multiple reducers into one
 * @param {Object} reducers - Object mapping state keys to reducer functions
 * @returns {Function} Combined reducer
 */
function combineReducers(reducers) {
    return (state = {}, action) => {
        let hasChanged = false;
        const nextState = {};

        for (const key in reducers) {
            const reducer = reducers[key];
            const prevKeyState = state[key];
            const nextKeyState = reducer(prevKeyState, action);
            nextState[key] = nextKeyState;
            hasChanged = hasChanged || nextKeyState !== prevKeyState;
        }

        return hasChanged ? nextState : state;
    };
}

// Selector helpers

/**
 * Create a memoized selector
 * @param {Function[]} inputSelectors - Selectors whose results are passed to resultFn
 * @param {Function} resultFn - Computes derived data from inputs
 * @returns {Function} Memoized selector
 */
function createSelector(...args) {
    const resultFn = args.pop();
    const inputSelectors = args;

    let lastInputs = null;
    let lastResult = null;

    return (state) => {
        const inputs = inputSelectors.map(sel => sel(state));
        const inputsChanged = !lastInputs || inputs.some((input, i) => input !== lastInputs[i]);

        if (inputsChanged) {
            lastInputs = inputs;
            lastResult = resultFn(...inputs);
        }

        return lastResult;
    };
}

/**
 * Subscribe to a slice of state with shallow equality check
 * @param {Object} store - Redux-like store
 * @param {Function} selector - (state) => slice
 * @param {Function} callback - Called when slice changes
 * @returns {Function} Unsubscribe function
 */
function subscribeToSlice(store, selector, callback) {
    let currentSlice = selector(store.getState());

    return store.subscribe(() => {
        const nextSlice = selector(store.getState());
        if (!shallowEqual(currentSlice, nextSlice)) {
            currentSlice = nextSlice;
            callback(nextSlice);
        }
    });
}

/**
 * Shallow equality check for objects
 */
function shallowEqual(a, b) {
    if (a === b) return true;
    if (!a || !b) return false;
    if (typeof a !== 'object' || typeof b !== 'object') return false;

    const keysA = Object.keys(a);
    const keysB = Object.keys(b);

    if (keysA.length !== keysB.length) return false;

    for (const key of keysA) {
        if (a[key] !== b[key]) return false;
    }

    return true;
}

// Action helpers

/**
 * Create an action creator
 * @param {string} type - Action type
 * @param {Function} payloadCreator - (...args) => payload
 * @returns {Function} Action creator
 */
function createAction(type, payloadCreator) {
    const creator = (...args) => ({
        type,
        payload: payloadCreator ? payloadCreator(...args) : args[0]
    });
    creator.type = type;
    creator.toString = () => type;
    return creator;
}

// Common middleware

/**
 * Thunk middleware - allows dispatching functions
 */
const thunkMiddleware = ({ dispatch, getState }) => (next) => (action) => {
    if (typeof action === 'function') {
        return action(dispatch, getState);
    }
    return next(action);
};

/**
 * Logging middleware for development
 */
const loggerMiddleware = ({ getState }) => (next) => (action) => {
    console.group(action.type);
    console.log('action:', action);
    const result = next(action);
    console.log('next state:', getState());
    console.groupEnd();
    return result;
};
